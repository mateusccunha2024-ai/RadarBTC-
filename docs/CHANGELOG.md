# Changelog

## V8 — atualização 10 (2026-11-27) · layout auto ajustável, páginas e tamanho da fonte
- Tamanho da fonte separado do zoom (Visualização → Tamanho da fonte: 85% a 150%), no PC e no celular. Só o texto muda; colunas de
  tabela, linhas dos níveis-chave e larguras mínimas dos painéis acompanham a fonte.
- Painéis lado a lado só enquanto cabem com a largura mínima de cada um. Quando não cabem (janela estreita, tablet, fonte grande),
  viram páginas com botões no topo ("Página: …"); cada página leva os painéis que cabem juntos. Nenhum painel é espremido.
- Celular: cada painel com título é uma página (ex.: Mapa | Níveis e visão geral; Top LONG | Top SHORT | Top geral | Alertas).
- Radar: com pouca altura (tablet deitado, fonte grande) ou no celular, "Mapa e níveis" e "Liquidações reais e alertas" viram duas páginas.
- Barra do topo e barra de abas do PC quebram linha em vez de cortar ou esconder abas fora da tela; títulos de painel e pares
  rótulo/valor também quebram linha.

## V8 — atualização 9 (2026-11-27) · gráfico de CVD com preço, horário e histórico
- Long/short: gráfico de CVD com períodos 5 min, 15 min, 1 h e 4 h (histórico desde que o app abriu; antes só 15 min).
- Preço do BTC sobreposto (linha laranja) com escala à direita (máximo, meio, mínimo e etiqueta do último preço); escala do CVD
  à esquerda; rodapé com data e hora.
- Mesmo modelo do mapa de liquidações: roda do mouse = zoom no tempo, arrastar = mover, duplo clique = voltar; passar o mouse
  (ou tocar) mostra pontos em cada linha, etiqueta de data/hora no rodapé e quadro com preço, CVD total e de cada corretora.

## V8 — atualização 8 (2026-11-27) · níveis de liquidação sem zerar e menos texto cortado
- Mapa estimado: a queda de open interest causada pelas próprias liquidações era descontada duas vezes (os níveis atravessados
  saíam do mapa e, de novo, a queda do OI encolhia o que sobrou). Em períodos longos (1–2 anos) com quedas fortes isso zerava
  Níveis-chave e Visão geral. Agora só a parte da queda que não veio de liquidações reduz os níveis restantes (teste novo).
- Tabelas: a última coluna quebra linha em vez de terminar em "…"; tabelas de liquidações reais mais compactas, cabem no painel do PC.
- Status do heatmap (carregando/erro) quebra linha em vez de cortar.

## V8 — atualização 7 (2026-11-26) · todos os níveis em aberto e mais corretoras no mapa
- Mapa de liquidações com 6 corretoras: além de Binance, Bybit e OKX, entram Gate (API guarda ~180 dias), HTX (últimos 200 pontos)
  e Kraken Futures (anos no diário) — as que publicam histórico de open interest. Bitget, Deribit e Hyperliquid só informam o OI atual.
- Radar: "Todos" mostra todos os níveis de liquidação em aberto (antes só os 6 maiores de cada lado e só os com ≥ 5% do maior);
  "Principais" volta ao resumo. As linhas do gráfico seguem a mesma escolha; níveis pequenos aparecem como linha fina, sem rótulo.
- Corrigido: com só "Em aberto" ligado em períodos longos (1 ano, 2 anos) o mapa ficava vazio (escala 0,0) quando o open interest
  de uma corretora caía mais do que o modelo acompanhava.

## V8 — atualização 6 (2026-11-23) · liquidações executadas × em aberto
- Radar: botões "Já executadas" e "Em aberto" para filtrar o mapa (os dois ligados = tudo). Níveis em aberto viram linhas
  do nascimento até a borda direita, nas cores e nos níveis das caixas "Níveis-chave" (short acima = vermelho, long abaixo = verde),
  com o tamanho em BTC; quando duas linhas ficam coladas, só o rótulo da maior aparece.
- Radar no PC com gráfico mais alto: "Mostrar" na mesma linha do período, legenda de cores numa linha só, mais espaço para o mapa
  que para as tabelas e botão "Ampliar gráfico", que esconde as tabelas de liquidações reais para o mapa ocupar a aba inteira.

## V8 — atualização 5 (2026-11-22) · redesenho visual
- Celular: filtros secundários (níveis, mín., intensidade, executado, centro; mapa e corretoras do heatmap; faixa e velas do footprint)
  ficam atrás de "Mais filtros ▾", com resumo do que está escolhido; os dados aparecem já na primeira tela.
- Texto mais legível: rótulos com no mínimo 12 sp, números e texto corrido com 13 sp; preço do topo maior (24 sp no PC, 20 sp no celular).
- Barra do topo do PC sem texto cortado: contadores de trades/liquidações saem (continuam na aba Status); aviso de livros cruzados quebra linha.
- Hierarquia: só o título de cada painel fica em caixa alta; abas, botões e cabeçalhos de tabela em caixa normal; sinal de fluxo em destaque (28 sp).
- Tudo em português: abas (Livro de ofertas, Paredes, Long/short, Radar, Zonas ímã…) e sinais (Pressão vendedora,
  Recompra de shorts…). Mantidos termos técnicos: Footprint, CVD, POC, OI, ETFs.
- Abas agrupadas por assunto: Livro, Fluxo, Liquidações e Mais (barra do PC com separadores; lista do celular com títulos de grupo).
- Livro de ofertas: célula vazia no lugar de "·"; valores abaixo de 0,001 BTC em cinza para os níveis relevantes se destacarem.
- Cores das corretoras nos gráficos (CVD) em tons frios; amarelo/laranja ficam só para aviso e acento.
- Heatmap de liquidações na escala de laranja do app (antes roxo/verde).
- Estados vazios mais baixos; a dica "deslize para o lado" aparece só no celular.
- Notas de rodapé escondidas por padrão; botão "Notas" na barra do topo do PC (e Visualização no celular) mostra/esconde.
- Heatmap navegável como gráfico de preço: roda do mouse = zoom no tempo (na escala de preço = zoom no preço), arrastar = mover,
  arrastar a escala = esticar o preço, duplo clique/toque duplo = período inteiro, pinça no celular; botões −, + e Ajustar.
- Marcações no heatmap: linha de preço, tendência, zona e texto; clicar numa marcação seleciona para apagar; ficam salvas entre aberturas.
- Painel de alertas (Radar): cada alerta em duas linhas — hora, tipo e lado (faixa na cor de compra/venda) e depois preço · corretora · números,
  sem cortar o texto; filtro Todos/Paredes/Liquidações/Execuções/Divergências; eventos repetidos no mesmo nível viram uma linha com "×N".

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
