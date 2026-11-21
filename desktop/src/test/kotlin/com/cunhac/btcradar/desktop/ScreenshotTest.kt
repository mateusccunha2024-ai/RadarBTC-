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
        Thread.sleep(90_000)
        val out = File("build/screens").apply { mkdirs() }

        fun shot(name: String, tab: Int, settings: Boolean = false, w: Int = 1600, h: Int = 900, density: Float = 1f) {
            val scene = ImageComposeScene(w, h, Density(density)) {
                RadarTheme { Terminal(ctx, initialTab = tab, showSettings = settings) }
            }
            var t = 0L
            repeat(6) { scene.render(t); t += 400_000_000L; Thread.sleep(250) }
            val img = scene.render(t)
            File(out, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }

        Look.useDark(true); UiScale.set(1.0f)
        TABS.indices.forEach { i -> shot("dark-%02d".format(i), i) }
        shot("dark-menu", 0, settings = true)
        Look.useDark(false)
        listOf(0, 1, 2, 6, 8, 9).forEach { i -> shot("light-%02d".format(i), i) }
        shot("light-menu", 0, settings = true)
        UiScale.set(1.25f)
        shot("light-zoom125-00", 0)
        // Celular em pé: 412 dp de largura; altura grande para mostrar a página inteira que rola.
        TABS.indices.forEach { i -> shot("phone-%02d".format(i), i, w = 618, h = 3600, density = 1.5f) }
        shot("phone-menu", 0, settings = true, w = 618, h = 1372, density = 1.5f)
        Look.useDark(false)
        shot("phone-light-00", 0, w = 618, h = 3600, density = 1.5f)
        Look.useDark(true); UiScale.set(1.0f)
        hub.stop()
    }
}
