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

/** Renderiza cada aba com dados REAIS (só com SHOTS=1) para revisão visual. */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenshotTest {
    @Test fun renderTabs() {
        assumeTrue(System.getenv("SHOTS") == "1")
        val hub = Hub()
        val db = SqliteHistory()
        hub.history = db
        hub.start()
        val map = MapModel(CoroutineScope(Dispatchers.Default))
        Thread.sleep(45_000)
        val out = File("build/screens").apply { mkdirs() }
        TABS.indices.forEach { i ->
            val scene = ImageComposeScene(1600, 900, Density(1f)) {
                RadarTheme { Terminal(AppContext(hub, db, map), initialTab = i) }
            }
            var t = 0L
            repeat(6) { scene.render(t); t += 400_000_000L; Thread.sleep(250) }
            val img = scene.render(t)
            File(out, "tab-$i.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        hub.stop()
    }
}
