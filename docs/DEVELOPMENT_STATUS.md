# Status do desenvolvimento — checkpoint 1 (2026-11-20)

| Recurso | Status | Evidência |
|---|---|---|
| Livro Bybit (1000, sequência) | PASS | ao vivo + regra validada |
| Livro OKX (400, sequência) | PASS | ao vivo + regra validada |
| Livro Binance | BLOCKED (CI) / a validar no PC | WebSocket ok; REST 451 nos EUA |
| Bybit RPI separado | PASS | canal ao vivo, sequência ok |
| Livro consolidado + filtros | PASS (lógica) / PARTIAL (tela não testada por clique) | teste unitário |
| Liquidity Walls + rastreamento | PASS (lógica + ao vivo: 457 ativas) | testes unitários |
| Trades, agressão, delta, CVD | PASS | ao vivo + carga |
| OI / funding / mark | PASS Bybit e OKX; Binance mark/funding PASS, OI BLOCKED (CI) | ao vivo |
| Sinais LONG/SHORT | PASS (inferência documentada) | testes |
| Large Trades | PASS (lógica) | compilação + ao vivo de trades |
| Liquidation Radar + heatmap | PASS (lógica) / PARTIAL (poucos eventos ao vivo) | testes + 1–2 eventos |
| Histórico SQLite + logs | PARTIAL | compila; validar no Windows |
| Inicialização Windows (sem servidor local, instância única) | PARTIAL | instalador gerado; validar no PC |
| MARKET FLOW CORRELATION | FAIL (não iniciado) | próxima fase |
| LIQUIDITY HISTORY (tela) | FAIL (não iniciado) | dados já gravados |
| Testes de interface por clique | FAIL (não iniciado) | próxima fase |

Próximo checkpoint: validar no PC do usuário (Binance no Brasil, telas, SQLite), depois Market Flow Correlation e Liquidity History.
