# Relatório de testes (verificação V8 #9)

- Compilação Windows (desktop) e Android (app): SUCCESSFUL.
- Unitários: ParseTest 9/9, SequenceTest 3/3, IntelligenceTest 5/5, LoadTest 1/1 — 0 falhas. Fixtures = mensagens reais capturadas em 2026-11-20 (não misturadas com produção).
- Carga (sintética, isolada): 300.000 trades em 56 ms; publicação 9 ms; atualização do livro 0,55 µs; cópia do livro 0,38 ms.
- Ao vivo (45 s, dados reais, servidor EUA): 2/3 fontes LIVE com livro válido (Bybit, OKX); Binance com WebSockets LIVE e REST bloqueado (451). Relatórios completos nas issues “Verificação V8 #N” do repositório.
- Como repetir: qualquer push na branch `v8` roda tudo e abre uma issue com o relatório.
