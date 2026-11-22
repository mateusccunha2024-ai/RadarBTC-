# Changelog

## V8 — atualização 4 (2026-11-22) · ajustes de celular
- Celular: a aba atual vira um botão "Abas ▾" que abre a lista das 12 abas, cada uma com uma linha explicando o que mostra.
- Celular: filtros, caixas de marcar e botões com área de toque de 48 dp (padrão do Android); campos de texto mais altos.
- LONG e SHORT usam as cores de compra e venda em todo o app (inclusive no modo daltonismo); pontos e escala do heatmap também.
- CVD com o zero e os extremos marcados em BTC; heatmap com legenda de cores (BTC por faixa) e dos pontos de liquidação.
- Footprint: preços do eixo inteiros, alinhados e sem sobrepor o preço atual.
- Tabelas largas avisam "deslize para o lado"; mensagens de tabela vazia ficam na área visível; textos que cortavam agora quebram linha.
- Status em português (Saiu, Executada (confirmada), Ao vivo…); números do motor sempre com vírgula decimal.
- Barra do topo do celular: "Livros 2/3" e corretoras em duas linhas; em telas estreitas o nome do app some para caber tudo.
- Corrige travamento do heatmap no celular com zoom de 125%.

## V8 — atualização 3 (2026-11-21) · terminal completo no Android
- O Android passa a rodar o mesmo terminal do Windows: todas as 12 abas (ORDERFLOW, FOOTPRINT, LIQUIDITY WALLS, LIQUIDITY HISTORY,
  MAGNET ZONES, LONG & SHORT FLOW, MARKET FLOW CORRELATION, LARGE TRADES, LIQUIDATION RADAR, ETFs, ALERTAS, STATUS),
  menu de visualização, tema claro/escuro e zoom. Código de tela único em `terminal/`, usado pelos dois apps.
- Layout de celular: painéis empilhados com rolagem, barra superior compacta, filtros que quebram linha, tabelas largas com rolagem lateral,
  medidas dos gráficos em dp; heatmap com toque (mesma leitura do mouse no PC).
- Aba ETFs (fluxo diário Farside, 5/20/60 pregões, por fundo) também no Windows.
- Android: histórico de paredes em SQLite, configurações salvas, notificações dos alertas no celular e opção
  "Continuar monitorando com o app fechado" (serviço em primeiro plano com notificação fixa; usa mais bateria).
- Telegram e e-mail funcionam também no celular.

## V8 — atualização 2 (2026-11-21)
- Liquidações reais de 6 corretoras: Binance, Bybit, OKX + Gate (`futures.public_liquidates`, size<0 = LONG, 1 contrato = 0,0001 BTC),
  Bitget (UTA v3 `liquidation`, valor em USDT, side = lado da posição) e Deribit (trades com campo `liquidation`, valor em USD).
  Formatos capturados ao vivo e conferidos contra o REST/movimento de preço.
- Nova aba FOOTPRINT: venda × compra agressora por faixa, desequilíbrio diagonal 3×, POC por vela, perfil de volume (POC/VAH/VAL), 24 h em memória.
- Paredes: ABSORÇÃO (execução ≥ 50% e tamanho mantido) e ICEBERG (execução reposta 2+ vezes), com painel e alerta.
- Heatmap: modos OI por preço de entrada e longs × shorts, faixa de delta dos níveis, filtro por alavancagem (10x/25x/50x/100x).
- CVD normalizado pelo OI somado; detecção de divergência preço × CVD em 5 min, 15 min e 1 h.
- Alertas externos por Telegram e e-mail (SMTP) com filtros, intervalo mínimo e limite de 20/min; nova aba ALERTAS.
- Novas abas MARKET FLOW CORRELATION (Pearson preço/CVD/OI/liquidações por minuto) e LIQUIDITY HISTORY (paredes encerradas do SQLite).
- Visual: tema claro/escuro, cores para daltonismo, densidade compacta, frequência de redesenho, abas visíveis, menu de visualização,
  zoom por botões/menu/atalhos; controles quebram linha em zoom alto.

## V8 — checkpoint 1 (2026-11-20)
- Novo motor compartilhado (Android + Windows): livros locais incrementais com sequência para Binance, Bybit e OKX; Bybit RPI separado.
- Livro consolidado com participação por corretora, agrupamento opcional e filtros.
- Liquidity Walls com estados NEW/INCREASED/REDUCED/DISAPPEARED/POSSIBLY_EXECUTED/EXECUTION_CORROBORATED.
- Fluxo agressor, delta, CVD, sinais LONG/SHORT inferenciais, grandes execuções.
- Liquidation Radar em BTC com rankings, classificação e heatmap.
- Windows: terminal Quant Orderflow com 7 abas, SQLite, logs com rotação, configurações salvas, instância única.
- Correções: rotas Binance `/public` e `/market`; OKX porta 443.

## 0.1 (2026-11-20)
- Primeira versão: mapa estimado, liquidações, ETFs.
