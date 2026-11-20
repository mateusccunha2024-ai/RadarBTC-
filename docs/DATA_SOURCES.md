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
