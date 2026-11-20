# Estado das conexões (teste ao vivo de 45 s no GitHub Actions, servidor nos EUA)

| Fonte | Resultado | Detalhe |
|---|---|---|
| Bybit livro 1000 | LIVE | 1000/1000 níveis, 50–98 ms, 0 reconstruções |
| Bybit RPI | LIVE | sequência ok; 0 níveis RPI no momento |
| Bybit trades/tickers/liquidações | LIVE | OI ≈ 69.060 BTC |
| OKX livro 400 | LIVE | 400/400 níveis, 50–79 ms, 0 reconstruções |
| OKX trades/OI/funding/mark/liquidações | LIVE | OI ≈ 33.180 BTC; ctVal pelos metadados |
| Binance /market (trades, mark, funding, forceOrder) | LIVE | 1.045–1.723 mensagens em 45 s |
| Binance /public depth | LIVE (WebSocket) | 420 mensagens |
| Binance snapshot REST e OI REST | **BLOCKED no servidor de teste** | HTTP 451 (restrição geográfica EUA). Deve funcionar no Brasil — validar no PC do usuário. Sem snapshot o livro Binance fica marcado INVÁLIDO e fora da consolidação. |
