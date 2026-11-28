package com.cunhac.btcradar.desktop

/*
 * Código comum do terminal (Windows e Android).
 * Cada plataforma fornece, no mesmo pacote, as mesmas peças com implementação própria:
 *   AppDirs, FileLog, SettingsStore, SqliteHistory (+ wallEvents), argbImage(), processStats(), Platform e JarvisNative
 *   (IA, voz e microfone locais do assistente).
 * Windows: desktop/src/main/kotlin/.../Persistence.kt e DesktopPlatform.kt
 * Android: app/src/main/java/.../desktop/AndroidPlatform.kt
 */

/** Evento de parede gravado (aba LIQUIDITY HISTORY). */
data class WallRecord(val tsMs: Long, val venue: com.cunhac.btcradar.engine.Venue, val bid: Boolean, val price: Double, val status: String,
                      val cls: String, val peak: Double, val current: Double, val firstSeenMs: Long, val lastSeenMs: Long, val tradedNear: Double)
