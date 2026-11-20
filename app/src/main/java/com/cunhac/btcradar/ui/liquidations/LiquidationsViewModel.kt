package com.cunhac.btcradar.ui.liquidations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cunhac.btcradar.data.exchanges.LiquidationStream
import com.cunhac.btcradar.data.model.Exchange
import com.cunhac.btcradar.data.model.Liquidation
import com.cunhac.btcradar.data.model.Side
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LiqState(
    val recent: List<Liquidation> = emptyList(),   // mais recentes primeiro
    val longUsd1h: Double = 0.0,
    val shortUsd1h: Double = 0.0,
    val byExchange1h: Map<Exchange, Double> = emptyMap(),
    val largest1h: Liquidation? = null,
    val minIndex: Int = 0,
)

class LiquidationsViewModel : ViewModel() {
    val minOptions = listOf(0.0, 10_000.0, 100_000.0)

    private val window = ArrayDeque<Liquidation>() // última 1h
    private val _state = MutableStateFlow(LiqState())
    val state: StateFlow<LiqState> = _state

    init {
        viewModelScope.launch {
            LiquidationStream.all().collect { add(it) }
        }
    }

    fun setMin(i: Int) {
        _state.update { it.copy(minIndex = i) }
        publish()
    }

    private fun add(l: Liquidation) {
        window.addFirst(l)
        val cutoff = System.currentTimeMillis() - 3_600_000
        while (window.isNotEmpty() && window.last().timeMs < cutoff) window.removeAt(window.size - 1)
        publish()
    }

    private fun publish() {
        val min = minOptions[_state.value.minIndex]
        _state.update {
            it.copy(
                recent = window.asSequence().filter { l -> l.usd >= min }.take(200).toList(),
                longUsd1h = window.filter { l -> l.side == Side.LONG }.sumOf { l -> l.usd },
                shortUsd1h = window.filter { l -> l.side == Side.SHORT }.sumOf { l -> l.usd },
                byExchange1h = window.groupBy { l -> l.exchange }.mapValues { e -> e.value.sumOf { l -> l.usd } },
                largest1h = window.maxByOrNull { l -> l.usd },
            )
        }
    }
}
