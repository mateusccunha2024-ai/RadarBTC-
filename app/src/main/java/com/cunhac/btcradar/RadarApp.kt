package com.cunhac.btcradar

import android.app.Application
import com.cunhac.btcradar.desktop.Platform

class RadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Platform.init(this)
        // Guarda o motivo se algum erro fechar o app (o Jarvis mostra na próxima vez).
        runCatching { com.cunhac.btcradar.desktop.JarvisCrash.install() }
    }
}
