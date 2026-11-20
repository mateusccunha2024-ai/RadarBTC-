# Auditoria — Radar BTC antes do V8 (2026-11-20)

Base escolhida pelo usuário: **atualizar o Radar BTC** (Kotlin, Android + Windows). Os arquivos `BTC_Orderflow_V8_Alpha2_*` não foram fornecidos e não foram usados.

## Problemas encontrados (verificados com dados reais)
| # | Problema | Evidência | Ação |
|---|---|---|---|
| 1 | Liquidações Binance mudas | Desde 2026-04-23 a Binance separou `/public` e `/market`; `wss://fstream.binance.com/ws/btcusdt@forceOrder` não entregou mensagens (timeout) | Rotas novas: depth em `/public`, aggTrade/markPrice/forceOrder em `/market` |
| 2 | OKX porta 8443 | Servidor enviou `code 64010: WebSocket port 8443 will be retired soon` | Porta 443 |
| 3 | Livros por REST a cada 30 s, sem sequência | Código do mapa | Livros locais incrementais com validação de sequência |
| 4 | Tamanhos em US$ | Telas antigas | V8 exibe tudo em BTC |
| 5 | Checksum OKX | Campo `checksum: 0` nas mensagens (descontinuado) | Validação por `seqId/prevSeqId` |

## Problema histórico “0/3 fontes / 127.0.0.1:8787 ERR_CONNECTION_REFUSED”
Pertencia à arquitetura Node + navegador. O V8 do Radar BTC **não tem servidor local nem porta**: o motor roda dentro do próprio app. Essa classe de falha deixa de existir. O diagnóstico equivalente é a aba STATUS + `%USERPROFILE%\.radarbtc\logs`.
