# Validação do livro de ordens

- Regras de sequência conferidas com mensagens reais antes de implementar: Bybit 138 msgs livro + 258 RPI sem quebra da regra `u+1`; OKX 199 msgs sem quebra de `prevSeqId`; Binance 148 msgs sem quebra de `pu`.
- Testes unitários: regras Binance (buffer/descartar/aplicar/gap), primeiro evento precisa cobrir o snapshot, remoção de nível com quantidade 0, livro cruzado invalida.
- Livro inválido: não entra na consolidação (a tela lista “fora da consolidação”), e o rastreador de paredes não conclui desaparecimentos.
- Reconstrução: Binance refaz snapshot REST; Bybit e OKX cancelam e refazem a inscrição do canal.
- Consolidação: preços exatos (padrão) ou faixas; teste com 4,20 + 3,80 BTC no mesmo preço = 8,00 BTC e corretora inválida reportada.
