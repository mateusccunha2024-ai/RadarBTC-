# Fontes de dados (consultadas e validadas em 2026-11-20)

| Corretora | Endpoint | Canais | Unidade | Profundidade | Sequência |
|---|---|---|---|---|---|
| Binance USD-M | `wss://fstream.binance.com/public/stream` | `btcusdt@depth@100ms` | BTC | snapshot REST `/fapi/v1/depth?limit=1000` + diferencial | `U ≤ lastUpdateId ≤ u`, depois `pu = u anterior` |
| Binance USD-M | `wss://fstream.binance.com/market/stream` | `aggTrade`, `markPrice@1s`, `forceOrder` | BTC | — | — |
| Binance USD-M | REST `/fapi/v1/openInterest` (15 s) | OI | BTC | — | sem WebSocket oficial de OI |
| Bybit linear | `wss://stream.bybit.com/v5/public/linear` | `orderbook.1000`, `orderbook.rpi`, `publicTrade`, `tickers`, `allLiquidation` | BTC | 1000 níveis (200 ms); RPI 50 níveis | `u` +1 por mensagem; snapshot ou `u=1` reinicia |
| OKX swap | `wss://ws.okx.com:443/ws/v5/public` | `books`, `trades`, `open-interest`, `funding-rate`, `mark-price`, `liquidation-orders` | contratos → BTC | 400 níveis | `prevSeqId = seqId anterior` |

## Conversões
- OKX: `BTC = contratos × ctVal`, com `ctVal` lido de `/api/v5/public/instruments` (valor atual: 0,01 BTC, `ctValCcy=BTC`). Liquidações guardam a quantidade original em contratos.
- Binance e Bybit publicam em BTC: sem conversão.

## Semântica por corretora
- Agressor: Binance `m=true` → vendedor agressor; Bybit `S` = lado do taker; OKX `side` = lado do taker.
- Liquidação: Binance `S=SELL` → LONG liquidado; Bybit `S=Buy` → LONG liquidado (doc v5); OKX `posSide` (ou `side=sell` → LONG).

## RPI Bybit
Canal `orderbook.rpi.BTCUSDT`: cada nível = [preço, qtd não-RPI, qtd RPI]. Guardamos **só a parte RPI** em livro separado para não duplicar o livro orgânico; somar é opcional (botão “Bybit RPI”).


## Liquidações adicionais (atualização 2)
| Corretora | Canal | Quantidade | Lado |
|---|---|---|---|
| Gate | `wss://fx-ws.gateio.ws/v4/ws/usdt` · `futures.public_liquidates` BTC_USDT | contratos × `quanto_multiplier` (0,0001 BTC) | size < 0 = ordem de venda = LONG liquidado |
| Bitget | `wss://ws.bitget.com/v3/ws/public` · `{instType: usdt-futures, topic: liquidation}` | `amount` em USDT ÷ preço | `side` = lado da posição (sell = SHORT) |
| Deribit | `wss://www.deribit.com/ws/api/v2` · `trades.BTC-PERPETUAL.100ms` | `amount` em USD ÷ preço (inverso) | campo `liquidation`: T = agressor, M = passivo, MT = ambos |

Essas três corretoras entram só nas liquidações reais (sem livro, fluxo ou open interest).

## Mapa de liquidações estimadas (histórico de open interest, REST)

| Corretora | Endpoint | Unidade | Quanto histórico |
|---|---|---|---|
| Binance | `fapi.binance.com/futures/data/openInterestHist` | `sumOpenInterest` (BTC) | ~30 dias |
| Bybit | `api.bybit.com/v5/market/open-interest` (linear BTCUSDT) | BTC | anos |
| OKX | `okx.com/api/v5/rubik/stat/contracts/open-interest-history` (BTC-USDT-SWAP) | `oiCcy` (BTC) | anos |
| Gate | `api.gateio.ws/api/v4/futures/usdt/contract_stats` (BTC_USDT) | `open_interest_usd` ÷ `mark_price` | ~180 dias |
| HTX | `api.hbdm.com/linear-swap-api/v1/swap_his_open_interest` (BTC-USDT, `amount_type=2`) | `volume` (BTC) | últimos 200 pontos (60 min/4 h/1 dia) |
| Kraken Futures | `futures.kraken.com/api/charts/v1/analytics/PF_XBTUSD/open-interest` | BTC (fechamento do intervalo) | anos |

Bitget, Deribit e Hyperliquid publicam só o open interest atual, sem histórico — por isso não entram no mapa estimado.
