# Radar BTC

App Android (Kotlin + Jetpack Compose): mapa de liquidez do Bitcoin, liquidações ao vivo (Binance, Bybit, OKX) e fluxo dos ETFs spot de BTC nos EUA.

## Baixar o APK
Vá em **Releases** (lateral direita do repositório) e baixe o arquivo `.apk` mais recente. Cada commit na `main` gera um build novo automaticamente.

## Telas
- **Mapa**: preço, funding, OI, zonas de atração e escada de preço com liquidações estimadas + paredes do book.
- **Liquidações**: feed ao vivo, totais da última hora e por corretora.
- **ETFs**: fluxo do último pregão, 5 e 20 dias, gráfico de 30 pregões e quebra por fundo (fonte: Farside).

## Observações
- O mapa de liquidações é uma estimativa (como no CoinGlass): usa variação do open interest + alavancagens típicas.
- Ordens ocultas (icebergs) não aparecem em dados públicos; o app mostra as paredes visíveis do book.
