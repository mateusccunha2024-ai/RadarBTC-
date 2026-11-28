package com.cunhac.btcradar

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Modifier
import com.cunhac.btcradar.desktop.JarvisNative
import com.cunhac.btcradar.desktop.JarvisSelfTest
import com.cunhac.btcradar.desktop.Platform
import com.cunhac.btcradar.desktop.Q
import com.cunhac.btcradar.desktop.RadarCore
import com.cunhac.btcradar.desktop.Terminal
import com.cunhac.btcradar.ui.theme.RadarTheme

/** O celular usa o mesmo terminal do Windows, com layout adaptado à tela. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && !Platform.canNotify()) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        if (Platform.backgroundOn) runCatching { Platform.setBackground(true) }
        JarvisNative.activity = java.lang.ref.WeakReference(this)
        val ctx = RadarCore.ctx
        // "jarvis": abre já com o assistente aberto. "jarvisSelfTest" (só na versão de teste): baixa os modelos e usa tudo, para o teste no emulador.
        val openJarvis = intent?.getBooleanExtra("jarvis", false) == true
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent?.getBooleanExtra("jarvisSelfTest", false) == true) JarvisSelfTest.start(ctx)
        setContent {
            RadarTheme {
                Box(Modifier.fillMaxSize().background(Q.Bg).systemBarsPadding()) { Terminal(ctx, showJarvis = openJarvis) }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == JarvisNative.REQ_MIC) JarvisNative.onPermission(grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
}
