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
