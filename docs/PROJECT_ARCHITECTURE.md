# Arquitetura V8

```
BTC DATA ENGINE (app/src/main/java/.../engine, compartilhado Android + Windows)
  WsConn        1 conexão por rota, ping de aplicação, reconexão com backoff
  Feeds.kt      BinanceFeed (2 conexões: /public e /market) · BybitFeed (1) · OkxFeed (1)
  LocalBook     livro por corretora (BTC), sequência, invalidação, latência
  Parsers.kt    decodificação pura por corretora + BinanceSync
  Hub.kt        estrutura central; publica TerminalState a cada 1.500 ms

BTC INTELLIGENCE ENGINE
  Consolidate   livro consolidado (exato ou agrupado) com participação por corretora
  Walls.kt      Liquidity Walls + rastreamento temporal
  Flow.kt       agressão, delta, CVD, janelas, grandes execuções, sinais LONG/SHORT
  LiqRadar.kt   liquidações reais, rankings, heatmap
  LiquidityMap  (já existia) zonas estimadas = MAGNET ZONES

BTC ORDERFLOW APP (desktop/)
  Terminal + abas ORDERFLOW, LIQUIDITY WALLS, MAGNET ZONES, LONG & SHORT FLOW,
  LARGE TRADES, LIQUIDATION RADAR, STATUS
  Persistence: SQLite (histórico), logs com rotação, configurações (registro do usuário)
```
Regras: eventos são processados na chegada (threads do OkHttp); a interface só lê fotos do hub. Trocar de aba não abre nem fecha conexões. Nenhum painel cria conexão própria.
