# Radar macro-geopolítico do BTC

Aba **Radar macro** (grupo Macro), no PC e no celular. Mostra o contexto macro dos EUA e a geopolítica que costumam mexer
com o BTC, junta tudo num score de −100 a +100 e cruza o resultado com o order flow do próprio app.
**Nada aqui é previsão de preço**, e o app não identifica participantes individuais.

## Arquivos
| Arquivo | O que faz |
|---|---|
| `app/src/main/java/.../engine/MacroModel.kt` | Modelos, regras de notícias, perfis de indicadores, score, correlações e confluência (lógica pura, testada sem rede) |
| `app/src/main/java/.../engine/MacroSources.kt` | URLs e leitura (parse) de cada fonte; preenche o resultado oficial dos eventos do calendário |
| `terminal/src/main/kotlin/.../desktop/MacroEngine.kt` | Coleta com cache, intervalos por fonte, espera após falha, alertas sem repetição, configuração |
| `terminal/src/main/kotlin/.../desktop/MacroTab.kt` | Tela: Visão geral, Indicadores, Calendário, Notícias, Ajustes e fontes |
| `desktop/src/test/kotlin/.../engine/MacroTest.kt` | Testes |

## Fontes (todas públicas e gratuitas, sem chave)
| Dado | Fonte | Intervalo | Observação |
|---|---|---|---|
| S&P 500, Nasdaq 100, Dow, VIX, DXY, Treasury 10a intradiário, ouro, WTI, Brent, BTC | Yahoo Finance chart API (não oficial) | 5 min | Barras de 1 h, 1 mês |
| Treasury 2 e 10 anos | U.S. Treasury, Daily Par Yield Curve (CSV) | 60 min | Fechamento oficial |
| Fed funds efetiva e intervalo-alvo | NY Fed Markets API | 3 h | |
| CPI, núcleo, payroll, desemprego, salário/hora | BLS Public API v1 | 12 h | Limite de 25 consultas/dia por IP; o app usa 2 |
| PCE e núcleo do PCE | BEA via DBnomics | 12 h | |
| Saldo do TGA | Treasury Fiscal Data (Daily Treasury Statement) | 6 h | |
| Calendário: horário, impacto, expectativa, anterior | faireconomy.media (Forex Factory, semana atual) | 60 min | Não oficial; não traz o resultado |
| Reuniões do FOMC | federalreserve.gov | 24 h | |
| Notícias | Google News RSS (6 temas, últimas 24 h), Fed press releases (RSS), GDELT DOC 2.0 | 20–30 min | GDELT limita consultas; é complemento |

### Fontes adicionadas na atualização 12
| Dado | Fonte | Intervalo |
|---|---|---|
| Russell 2000, FTSE, DAX, Nikkei, Hang Seng, Xangai, Ibovespa, euro, iene, yuan, real, prata, cobre, gás natural, Treasuries 13 semanas / 5 / 30 anos, MOVE, HYG, ETH, IBIT, MSTR, COIN | Yahoo Finance | 10 min |
| Futuros de Fed funds dos próximos 8 meses (taxa implícita = 100 − preço) | CBOT via Yahoo Finance | 30 min |
| Probabilidades da próxima decisão do Fed | Kalshi (API pública) | 30 min |
| Juro real de 10 anos (TIPS) e inflação implícita | U.S. Treasury (curva real) | 60 min |
| SOFR e reverse repo overnight | NY Fed | 3 h |
| Balanço semanal do Fed e reservas bancárias | Federal Reserve H.4.1 | 6 h |
| Fear & Greed cripto | Alternative.me | 60 min |
| Valor de mercado cripto, dominância BTC/ETH, volume | CoinGecko | 30 min |
| Estoque de stablecoins | DefiLlama | 6 h |
| Hashrate e taxas da rede | mempool.space | 3 h / 15 min |
| Taxa de depósito do BCE | BCE (Data Portal) | 12 h |
| Dívida pública dos EUA | Treasury Fiscal Data (Debt to the Penny) | 12 h |
| Notícias: SEC, CFTC, BCE, CoinDesk, Cointelegraph, Decrypt, The Block, Bitcoin Magazine, CNBC, MarketWatch, WSJ, NYT, BBC, Al Jazeera, FT, Bloomberg, DW, Investing.com, OilPrice | RSS públicos | 30 min |

A última página da aba (Fontes) lista todas as fontes com link e a situação de cada coleta.

Fluxo dos ETFs spot vem da aba ETFs (Farside). Cache em `~/.radarbtc/macro` (Windows) e na pasta interna do app (Android):
ao abrir o app, os dados salvos aparecem na hora; se uma fonte falha, a última cópia continua em uso e a aba Ajustes e fontes
mostra "usando cópia salva". Depois de falhar, a fonte espera até 15 min antes de nova tentativa.

## Configuração (opcional)
| Item | Onde | Para quê |
|---|---|---|
| `BLS_API_KEY` | variável de ambiente, ou campo "Chave da API do BLS" na aba | API v2 do BLS (500 consultas/dia); sem ela tudo funciona |
| Pesos do score | Ajustes e fontes | 0 a 50 por fator; 0 desliga |
| Antecedência dos avisos | Ajustes e fontes | 5 a 240 min (padrão 30) |
| Tipos de alerta | aba Alertas | Os 6 alertas macro entram na mesma lista e nos mesmos canais (notificação, Telegram, e-mail) |

Chaves ficam só no aparelho (Preferências do usuário no Windows, SharedPreferences no Android). Nenhuma chave vai no código.

## Resultado vs expectativa (sem inventar)
O resultado de um evento só é preenchido quando a série oficial (BLS/BEA) já traz o **mês de referência** do evento
(mês anterior ao da divulgação) e o evento já passou. Hoje isso cobre CPI m/m e a/a, núcleo do CPI m/m, PCE e núcleo do PCE m/m,
payroll, desemprego e salário/hora m/m, calculados do índice oficial dessazonalizado (pode diferir em 0,1 do número
arredondado da divulgação). Nos demais eventos aparece "aguardando fonte oficial".
Comparação: percentuais dentro de ±0,05 pp e outros dentro de ±2% da expectativa = "em linha".

Direção quando o dado vem **acima** do esperado (abaixo = o contrário):
inflação (CPI, PCE, PPI) e salários → desfavorável · payroll → desfavorável no curto prazo · desemprego/pedidos de
seguro → favorável (até virar medo de recessão) · juros do FOMC → desfavorável · PIB, ISM, PMI, varejo → indeterminado.

## BTC Macro Sentiment (−100 a +100)
Cada fator vira uma leitura de −1 a +1. Score = Σ(leitura × peso) ÷ Σ(pesos com dado) × 100.
Confiança = parte dos pesos com dado válido. Abaixo de 35% o score não é mostrado. Dados mensais com mais de 3 meses
de defasagem são marcados "desatualizado" e ficam fora.

| Fator | Peso | Regra |
|---|---|---|
| Política monetária | 15 | Média de: EFFR − Treasury 2a (±0,75 pp = ±1) e futuros de Fed funds ~3 meses − EFFR (−0,5 pp = +1). Cortes precificados = favorável |
| Inflação | 12 | Núcleo do CPI: 12 meses − 3 meses anualizado; ±1 pp = ±1 (desacelerando = favorável) |
| Mercado de trabalho | 8 | Desemprego em 6 meses: +0,3 a +0,5 pp → +0,3; acima de +0,5 → −0,5; queda ≥ 0,3 → −0,2; salário > 4% a/a → −0,3 |
| Dólar (DXY) | 12 | 5 pregões: ±1,5% = ∓1 |
| Treasury yields | 12 | Média de: 10a nominal em 5 dias (±20 bp = ∓1) e juro real 10a (TIPS) em 5 dias (±15 bp = ∓1) |
| Liquidez | 8 | Média de: TGA em ~20 dias úteis (±US$ 150 bi = ∓1), balanço do Fed na semana (±US$ 25 bi = ±1), stablecoins em 30 dias (±3% = ±1) |
| Mercado acionário | 10 | Nasdaq 100 em 5 pregões: ±3% = ±1 |
| Volatilidade | 8 | VIX < 15 +0,5 · 15–20 0 · 20–30 −0,5 · > 30 −1; salto > 15% no dia −0,3; MOVE > 130 −0,3, < 80 +0,2 |
| Geopolítica | 7 | Notícias das últimas 24 h: Σ(±intensidade × confiabilidade × 0,35), limitado a ±1 |
| Fluxo institucional | 8 | ETFs spot em 5 pregões: ±US$ 1 bi = ±1 |

Faixas: ≤ −60 extremamente desfavorável · ≤ −20 desfavorável · −19 a +19 neutro/misto · ≥ +20 favorável · ≥ +60 extremamente favorável.
Os pesos são uma escolha documentada (sensibilidade do BTC a juros, dólar, liquidez e apetite por risco), não um modelo estimado.

## Notícias
Só entram manchetes que casam com uma regra de `MacroRules.NEWS` (palavra-chave no começo de palavra). Cada regra define
categoria, direção, intensidade, horizonte, justificativa e a cadeia "evento → indicador → mercado americano → BTC".
Manchete sem ligação econômica clara fica de fora. Confiabilidade: fonte oficial (Fed, Tesouro, BLS, BEA, SEC, Casa Branca),
alta (Reuters, AP, Bloomberg, FT, WSJ, CNBC, BBC etc.), média (demais). Repetidas entre veículos (títulos com ≥ 60% das
palavras iguais) viram uma só, mantendo a fonte mais confiável. Fora: mais de 48 h ou data no futuro.

## Leitura integrada com o order flow
Macro (faixa do score) × fluxo agressor de 15 min (delta/volume > +10% comprador, < −10% vendedor) × liquidações reais de 1 h
(lado com ≥ 1,5× o outro e ≥ 5 BTC no total) × absorções nas paredes (15 min).
- Cenário A: macro desfavorável + venda agressora + LONG liquidados → queda reforçada por pressão macro e desalavancagem.
- Cenário B: macro desfavorável + absorção na compra e preço sustentado → mercado absorvendo a pressão vendedora.
- Cenário C: macro favorável + compra agressora → alta alinhada ao macro (reforçada se SHORT liquidados).
- Fluxo contra o macro → divergência, confluência baixa.

## Alertas (cada ocorrência uma vez; histórico em `macro/alerts-fired.txt`)
| Tipo | Quando |
|---|---|
| Evento de alto impacto | Evento "High" dos EUA ou decisão do FOMC dentro da antecedência configurada |
| Dado acima/abaixo do esperado | Resultado oficial fora da faixa "em linha", até 6 h após a divulgação |
| DXY, yields ou VIX | DXY ±0,6% no dia · 10a ±10 bp no dia · VIX +15% no dia |
| Geopolítica/regulação | Notícia de intensidade alta de fonte alta/oficial nas últimas 3 h (até 3 por ciclo) |
| Divergência BTC × EUA | BTC ±0,8% e Nasdaq 100 ∓0,5% na mesma hora, com o pregão aberto |
| Score | Mudança ≥ 25 pontos ou troca de faixa |

## Limitações
- Yahoo Finance e o calendário do Forex Factory não são APIs oficiais e podem mudar sem aviso.
- O calendário cobre só a semana atual; a próxima semana aparece quando é publicada (domingo à noite).
- Não há consenso de mercado fora desse calendário. A expectativa de juros vem da Kalshi e dos futuros de Fed funds (o FedWatch da CME é pago).
- O FRED não é usado (exige chave e não respondeu no teste); o balanço do Fed vem direto da página H.4.1, que é semanal.
- Treasury 2 anos só no fechamento oficial (sem intradiário gratuito confiável).
- A classificação de notícias é por regras de manchete: ironia, negação e contexto podem enganar; a matéria completa está no link.
- Correlações usam só horários em que os dois ativos negociam; fora do pregão americano não há dado novo.
- Fear & Greed é mostrado, mas não entra no score, para não contar duas vezes o sentimento que o order flow já mostra.
- Kalshi, CoinGecko, DefiLlama, Alternative.me e mempool.space são serviços privados e podem limitar ou mudar o acesso.
