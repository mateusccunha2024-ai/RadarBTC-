package com.cunhac.btcradar.ui.etf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cunhac.btcradar.data.etf.EtfFlowSource
import com.cunhac.btcradar.data.etf.FarsideEtfSource
import com.cunhac.btcradar.data.model.EtfDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class EtfState(
    val loading: Boolean = true,
    val error: String? = null,
    val days: List<EtfDay> = emptyList(), // mais recente primeiro
) {
    val latest: EtfDay? get() = days.firstOrNull()
    fun sum(n: Int) = days.take(n).sumOf { it.total }
}

class EtfViewModel : ViewModel() {
    private val source: EtfFlowSource = FarsideEtfSource
    private val _state = MutableStateFlow(EtfState())
    val state: StateFlow<EtfState> = _state

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _state.value = runCatching { source.fetch() }.fold(
                onSuccess = { EtfState(loading = false, days = it) },
                onFailure = { EtfState(loading = false, error = "Não foi possível carregar os fluxos dos ETFs (${it.message})") },
            )
        }
    }
}
