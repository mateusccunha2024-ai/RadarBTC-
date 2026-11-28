package com.cunhac.btcradar.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.cunhac.btcradar.engine.Hub
import com.cunhac.btcradar.ui.theme.RadarTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Renderiza cada aba com dados REAIS (só com SHOTS=1) para revisão visual: tema escuro, tema claro, zoom e menu. */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotTest {
    @Test fun renderTabs() {
        assumeTrue(System.getenv("SHOTS") == "1")
        val hub = Hub()
        val db = SqliteHistory()
        hub.history = db
        hub.start()
        val ctx = AppContext(hub, db, MapModel(CoroutineScope(Dispatchers.Default)))
        ctx.macro.start()
        Thread.sleep(90_000)
        val out = File("build/screens").apply { mkdirs() }

        fun shot(name: String, tab: Int, settings: Boolean = false, w: Int = 1600, h: Int = 900, density: Float = 1f, tabs: Boolean = false, jarvis: Boolean = false) {
            val scene = ImageComposeScene(w, h, Density(density)) {
                RadarTheme { Terminal(ctx, initialTab = tab, showSettings = settings, showTabs = tabs, showJarvis = jarvis) }
            }
            var t = 0L
            repeat(6) { scene.render(t); t += 400_000_000L; Thread.sleep(250) }
            val img = scene.render(t)
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }

        Look.useDark(true); UiScale.set(1.0f)
        // Assistente (Jarvis): painel vazio, depois perguntas reais (dados do app e o fluxo de trade completo).
        I18n.use("pt")
        shot("jarvis-00-empty", 0, jarvis = true)
        shot("phone-jarvis-00-empty", 0, w = 618, h = 1372, density = 1.5f, jarvis = true)
        JarvisSettings.useVoiceOn(false)
        fun askWait(q: String) { val n = Jarvis.messages.size; Jarvis.ask(ctx, q); var k = 0; while ((Jarvis.busy || Jarvis.messages.size < n + 2) && k++ < 240) Thread.sleep(500) }
        askWait("Como está o BTC agora?")
        shot("jarvis-01-summary", 0, jarvis = true, h = 1100)
        askWait("Plano de trade BTC")
        shot("jarvis-02-btc", 0, jarvis = true, h = 2400)
        Jarvis.clear(); askWait("Time Machine TSLA")
        shot("jarvis-03-tsla", 0, jarvis = true, h = 2400)
        shot("phone-jarvis-03-tsla", 0, w = 618, h = 3600, density = 1.5f, jarvis = true)
        Jarvis.clear(); askWait("Scanner de ações")
        shot("jarvis-04-scan", 0, jarvis = true, h = 1400)
        Jarvis.clear(); askWait("Resumo macro de hoje"); askWait("Onde estão as paredes?")
        shot("jarvis-05-macro-walls", 0, jarvis = true, h = 1400)
        // Atualização 17: conversa sem IA (cumprimento e conceito) e o mapa do cérebro
        Jarvis.clear(); askWait("oi, tudo bem?"); askWait("o que é funding e por que importa?"); askWait("Quanto está o funding?")
        shot("jarvis-06-conversa", 0, jarvis = true, h = 1200)
        Jarvis.clear()
        JarvisUi.initialView = JarvisView.MAP
        shot("jarvis-07-mapa", 0, jarvis = true, h = 1500)
        shot("phone-jarvis-07-mapa", 0, w = 618, h = 2600, density = 1.5f, jarvis = true)
        JarvisUi.initialView = JarvisView.CHAT
        TABS.indices.forEach { i -> shot("dark-%02d".format(i), i) }
        shot("dark-menu", 0, settings = true)
        Look.useDark(false)
        listOf(0, 1, 2, 6, 8, 9).forEach { i -> shot("light-%02d".format(i), i) }
        shot("light-menu", 0, settings = true)
        UiScale.set(1.25f)
        shot("light-zoom125-00", 0)
        // Celular em pé: 412 dp de largura (tamanho 100%); altura grande para mostrar a página inteira que rola.
        Look.useDark(true); UiScale.set(1.0f)
        TABS.indices.forEach { i -> shot("phone-%02d".format(i), i, w = 618, h = 3600, density = 1.5f) }
        shot("phone-menu", 0, settings = true, w = 618, h = 1372, density = 1.5f)
        shot("phone-tabs", 0, w = 618, h = 1372, density = 1.5f, tabs = true)
        Look.useDark(false)
        listOf(0, 5, 8).forEach { i -> shot("phone-light-%02d".format(i), i, w = 618, h = 3600, density = 1.5f) }
        Look.useDark(true); UiScale.set(1.25f)
        listOf(0, 8).forEach { i -> shot("phone-zoom125-%02d".format(i), i, w = 618, h = 3600, density = 1.5f) }
        // Layout auto ajustável: fonte 130% no PC, tablet deitado (1280 × 800, a 100% e a 70% como no aparelho
        // do usuário) e celular com fonte grande. Painéis que não cabem viram páginas com botões.
        Look.useDark(true); UiScale.set(1.0f); UiScale.useFont(1.3f)
        listOf(0, 2, 5, 8, 11).forEach { i -> shot("font130-%02d".format(i), i) }
        shot("font130-menu", 0, settings = true)
        UiScale.useFont(1.0f)
        listOf(0, 8).forEach { i -> shot("tablet-%02d".format(i), i, w = 1280, h = 800) }
        UiScale.set(0.7f)
        shot("tablet-zoom70-08", 8, w = 1280, h = 800)
        UiScale.set(1.0f); UiScale.useFont(1.3f)
        listOf(0, 8).forEach { i -> shot("phone-font130-%02d".format(i), i, w = 618, h = 3600, density = 1.5f) }
        UiScale.useFont(1.0f)
        // Radar macro (aba 12): tema claro, fonte grande, tablet e celular com fonte grande.
        Look.useDark(false); shot("light-12", 12)
        Look.useDark(true); UiScale.useFont(1.3f); shot("font130-12", 12)
        shot("phone-font130-12", 12, w = 618, h = 3600, density = 1.5f)
        UiScale.useFont(1.0f); shot("tablet-12", 12, w = 1280, h = 800)
        Look.useDark(true); UiScale.set(1.0f)
        // Outros idiomas (tabelas prontas; o que faltar chega pela tradução automática — espera um pouco por ela).
        for (code in listOf("en", "es", "zh", "ja")) { I18n.use(code); shot("$code-12", 12) }
        Thread.sleep(8000)
        for (code in listOf("en", "es", "zh", "ja")) { I18n.use(code); shot("$code-12", 12); shot("$code-00", 0); shot("$code-settings", 0, settings = true) }
        I18n.use("en"); shot("phone-en-12", 12, w = 618, h = 3600, density = 1.5f); shot("en-08", 8)
        I18n.use("pt")
        hub.stop()
    }
}
