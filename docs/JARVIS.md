# Jarvis — assistente de IA do Radar BTC

Painel aberto pelo botão **Jarvis · IA** no topo (PC: ao lado das abas; celular: tela cheia).

## Como responde
1. A pergunta é classificada por palavras-chave (português, inglês, espanhol e outras) em assuntos: preço, livro, paredes, fluxo,
   liquidações, derivativos, ETFs, macro, notícias, agenda, horário da bolsa, status, trade e scanner. Tickers são reconhecidos
   por `$TSLA`, `TSLA` ou pelo nome ("Tesla", "Nvidia"…).
2. O app junta os **fatos** daquele assunto a partir do estado ao vivo (Hub, mapa de liquidez, ETFs, Radar macro). Cada fato tem a fonte.
3. Com a IA local pronta, os fatos vão num prompt que proíbe inventar números, notícias ou fontes e pede 2–5 frases no idioma do app.
   Sem a IA (não baixada, desligada ou aparelho sem suporte), a resposta é montada direto com os fatos.
4. Pedidos de trade e scanner não passam pela IA: os números vêm do cálculo do app.

## Fluxo de trade (porta do day-trader-skill, MIT)
| Etapa | O que o app faz |
| --- | --- |
| Scanner | Lista editável (até 25 ações): rompimento da abertura de 15 min hoje; no diário, máxima de fechamento de 20 dias, volta acima da média de 20, RSI14 < 30 e repique |
| Notícias | Manchetes do Yahoo Finance do ticker (BTC: BTC-USD) |
| Entrada | Abertura de 15 min; 1º candle de 5 min **completo** que fecha acima da máxima, dentro da janela (ações 9h45–15h30 NY; BTC até 23h UTC). Só compra |
| Saída | Stop no meio da abertura (arredondado para longe da entrada), alvo 2R; recusa stop acima da entrada ou apertado demais |
| Time Machine | Pool com cada candle das sessões completas anteriores (até 59) como razão ao fechamento anterior; 10.000 replays sorteando o mesmo horário ±2 candles, semente 19; stop e alvo no mesmo candle contam como stop. Leque com 2.000 caminhos. Histórico: a mesma regra em cada sessão passada |
| Veredito | Participação do alvo entre os replays que tocaram um lado, contra o equilíbrio 1/(1+R): < 5 pp "cara ou coroa", < 15 pp "pende", senão "forte" |
| Tamanho | Menor entre perda máx./risco, teto da posição, ordem máx. e caixa menos a reserva (ações inteiras; BTC em passos de 0,0001) |
| Risco | Stop, perda ≤ máx., R:R ≥ mínimo, risco do dia, trades do dia, ordem máx., bloqueados, bolsa aberta e janela, plano de hoje |
| Boleta | JSON bracket no formato do `place_stock_order` da Alpaca (paper). **Nunca enviada** |
| Alertas | Depois de "Sim, registrar" (paper trade): notificação de stop, alvo e hora de zerar, uma vez cada |

BTC não tem pregão: cada dia UTC (0h–24h, 288 candles) é tratado como uma sessão, zerando às 23h55 UTC. Dados: Binance
(candles de 5 min, 30 dias). Ações: Yahoo Finance (5 min, 60 dias; não oficial, pode atrasar). Calendário da NYSE 2026–2027 com
feriados e fechamentos às 13h. O sorteio usa o mesmo método e a mesma semente da skill, mas o gerador aleatório do Java: os
percentuais não batem casa a casa com o Python.

## Modelos (baixados uma vez, ficam no aparelho)
| Item | Modelo | Tamanho | Licença |
| --- | --- | --- | --- |
| IA leve | Qwen2.5-0.5B-Instruct Q4_K_M (GGUF) | 468 MB | Apache 2.0 |
| IA padrão | Qwen2.5-1.5B-Instruct Q4_K_M (GGUF) | 1,1 GB | Apache 2.0 |
| Voz | Kokoro-82M multi-idioma int8 (sherpa-onnx) | 126 MB | Apache 2.0 |
| Microfone | Whisper tiny / base int8 (sherpa-onnx) | 98 / 152 MB | MIT |

Motores: llama.cpp (`de.kherud:llama` no Windows, `net.ladenthin:llama-android` no Android) e sherpa-onnx 2.0.3.
Pasta: Windows `%USERPROFILE%\.radarbtc\jarvis`; Android, pasta interna do app. A IA local no Android exige Android 9+ e 64 bits;
abaixo disso, voz e microfone continuam funcionando e as respostas vêm dos dados do app.

## Voz
Kokoro com vozes por idioma (português: Alex, Dora, Santa; inglês britânico: George, Lewis; e outras). Alemão, russo e coreano usam a
voz inglesa (o Kokoro não tem esses idiomas). Nenhuma API paga ou serviço de terceiros é usado; não usamos acessos não oficiais a
serviços comerciais de voz.

## Inteligência que cresce (atualização 14)
| Peça | Como funciona |
| --- | --- |
| Checagem de números | Todo número da resposta da IA tem de existir nos dados usados (aceita formatos pt/en e arredondamento). Se não existir, a IA tenta de novo copiando os números; se insistir, a resposta volta a ser montada direto com os dados. |
| Ferramentas | Com a IA pronta, ela escolhe até 3 ferramentas antes de responder: `dados <assunto>`, `memoria <consulta>`, `comparar <A> <B>` (5 e 20 pregões + correlação de 60 dias), `noticias <ativo>`, `plano <ativo> [stop%] [alvoR]`. Linhas fora desse formato são ignoradas. |
| Memória | Pesquisa diária (1 vez a cada 20 h, só no Wi-Fi no celular): manchetes das últimas 48 h no Google News sobre BTC, Fed, a lista do scanner, perguntas sem resposta e habilidades ligadas. Guarda texto, fonte, data e quantas fontes confirmaram; mantém 30 dias. Com a IA pronta, faz um resumo por tema (também conferido número a número). |
| Placar | Cada plano de trade é guardado e conferido depois do fim da sessão: alvo, stop, nenhum ou sem gatilho. Mostra alvo previsto × real e a calibração (Brier). Entra nas respostas sobre o ativo. |
| Avaliações | 👍 vira exemplo de treino (Exportar exemplos de treino → `treino-jarvis.jsonl`, formato de conversa); 👎 vira pesquisa do dia seguinte. |
| Habilidades | Receitas de dados (palavras + buscas no Google News), nunca código. Uma palavra que aparece em 3 perguntas sem resposta vira proposta; o catálogo da nuvem também chega desligado. Só funcionam depois de "Aprovar". |
| Prova | 40 perguntas fixas: roteamento (assunto e ativo), perguntas com dados e respostas da IA com números conferidos. Nota 0–100 com histórico. O teste automático exige pelo menos 38/40 no roteamento. |
| Cérebro na nuvem | Supabase: login anônimo por aparelho, tabelas `jv_knowledge`, `jv_scores`, `jv_feedback` (cada aparelho só lê e grava as próprias linhas, RLS) e `jv_skills` (catálogo só leitura). Endereço e chave pública vêm dos segredos do GitHub no build (`JARVIS_CLOUD_URL`, `JARVIS_CLOUD_KEY`); a chave secreta nunca sai do servidor. |
| IA forte no PC | Qwen2.5 7B Instruct Q4_K_M (4,4 GB, Apache 2.0), para PCs com 16 GB de RAM. |

O ajuste fino semanal do modelo com os exemplos aprovados ainda é manual (precisa de GPU); os exemplos já saem prontos no formato certo.


## Aprende coisas novas e não fecha o app (atualização 15)
- **Curiosidade (`JarvisCuriosity`):** depois da pesquisa diária, lê as manchetes dos últimos 3 dias, acha nomes de 1 a 3 palavras
  com inicial maiúscula que aparecem em 3+ manchetes de 2+ fontes e que ainda não conhece, procura cada um na Wikipedia (no idioma
  do app, senão em inglês; só aceita página com o mesmo título, nunca desambiguação) e guarda as 2 primeiras frases com o link como
  conhecimento `conceito` (vale 1 ano). O termo entra na fila de pesquisa de notícias. Sem página, o termo é ignorado e não volta.
- **Proteção (`JarvisCrash`):** marca enquanto o painel está aberto e guarda o motivo de qualquer erro que derrube o app
  (`jarvis/ultimo-fechamento.txt`). Se o app morreu com o painel aberto, a próxima abertura é em modo seguro. Os modelos nativos
  continuam com as marcas "carregando/falhou" (quem fechou o app fica desligado até "Tentar de novo"). A IA só carrega com memória
  livre suficiente.
- **Teste no emulador:** o CI instala o APK de teste num Android 11 com 4 GB, abre o Jarvis, roda o autoteste (`jarvisSelfTest`,
  só na versão de teste: baixa IA leve, voz e microfone leve, faz 3 perguntas, fala e carrega o microfone) e guarda capturas e log
  no ramo `android-smoke`.

## IA em processo separado (atualização 16)
- A IA local não roda mais dentro do app. Windows: `JarvisLlmWorker.kt` (o próprio `RadarBTC.exe --jarvis-llm <porta> <token>`,
  conversa por um socket local 127.0.0.1). Android: `JarvisLlmService` no processo `:jarvis` (Messenger).
- Se o processo da IA cair, `JarvisNative.takeCrash()` devolve o motivo e o Jarvis desliga aquele modelo (`falhou-llm-*`, com o
  motivo), mostra no painel e responde com os dados. Windows: o motivo sai do relatório do Java (`jarvis/hs_err_pid*.log`) e de
  `jarvis/ia-processo.log`; Android 11+: do registro de saída do processo (`ApplicationExitInfo`).
- Testes: `JarvisLlmProcessTest` (Linux, com a IA leve se `JARVIS_TEST_MODEL` existir), job `windows-ia` (`RadarBTC.exe
  --jarvis-llm-test <modelo> <saída>`) e o autoteste do emulador (queda simulada + "Tentar de novo").
