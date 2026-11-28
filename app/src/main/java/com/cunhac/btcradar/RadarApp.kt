package com.cunhac.btcradar

import android.app.Application
import com.cunhac.btcradar.desktop.Platform

class RadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Processo da IA do Jarvis (":jarvis"): só roda o modelo, nada do app.
        if (android.os.Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName().endsWith(":jarvis")) return
        Platform.init(this)
        // Guarda o motivo se algum erro fechar o app (o Jarvis mostra na próxima vez).
        runCatching { com.cunhac.btcradar.desktop.JarvisCrash.install() }
    }
}
