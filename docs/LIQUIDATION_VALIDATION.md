# Validação de liquidações

- Decodificação validada com mensagens reais/documentadas das 3 corretoras; OKX filtra apenas BTC-USDT-SWAP do canal geral de SWAP (teste com SOXL descartado).
- Ao vivo: 1–2 liquidações BTC recebidas em 45 s (mercado calmo).
- Rankings por BTC equivalente e períodos 1m/5m/15m/1h/24h; heatmap tempo × preço testado.
- Cobertura: Binance publica no máximo 1 evento/s por símbolo (snapshot); OKX entrega eventos com horário de origem atrasado (as janelas usam o horário de recebimento e preservam o da corretora). Nenhum feed é tratado como completo.
