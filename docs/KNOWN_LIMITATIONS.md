# Limitações conhecidas

1. Binance REST (snapshot do livro e OI) bloqueado por geografia nos servidores do GitHub; precisa ser confirmado no Brasil.
2. Profundidade Bybit: usamos `orderbook.1000`. O canal `orderbook.full` + REST de 10.000 níveis (linear desde 2026-08-11) exige outro protocolo de buffer; fica para a próxima fase.
3. Livro Binance: snapshot de 1.000 níveis; níveis além disso só aparecem quando recebem atualização.
4. Paredes: o livro público é agregado por preço; não identifica ordens individuais nem autores. “Sumiu” ≠ “executou”.
5. Sinais LONG/SHORT são inferências, nunca fatos.
6. Liquidações: feeds públicos não são completos (ver LIQUIDATION_VALIDATION).
7. Ainda não implementado: aba MARKET FLOW CORRELATION, aba LIQUIDITY HISTORY (os dados já são gravados no SQLite), testes automatizados de clique na interface.
8. Android: recebeu o motor e as correções de endereço, mas as telas V8 são só do Windows nesta fase.
9. Windows sem assinatura de código: SmartScreen mostra aviso.
