# Validação do fluxo LONG & SHORT

- Agressor validado por corretora com mensagens reais (Binance `m`, Bybit `S`, OKX `side`).
- Ao vivo: 3.343–5.435 trades em 45 s nas 3 corretoras; delta e CVD calculados por corretora e consolidado.
- Carga sintética: 300.000 trades → ingestão 56 ms; 7 janelas × 4 fontes = 9 ms por publicação.
- Sinais (LONG-BIASED ACCUMULATION, SHORT COVERING, BUYING PRESSURE, SHORT-BIASED ACCUMULATION, LONG CLOSING, SELLING PRESSURE) testados por tabela de casos. São **inferências**: a tela mostra dados observados, interpretação, confiança e limitação.
- OI somado só quando as 3 corretoras reportam (evita saltos falsos). Com o OI da Binance bloqueado no servidor de teste, a variação de OI ficou indisponível lá.
