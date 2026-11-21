package com.cunhac.btcradar

import android.app.Application
import com.cunhac.btcradar.desktop.Platform

class RadarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Platform.init(this)
    }
}
