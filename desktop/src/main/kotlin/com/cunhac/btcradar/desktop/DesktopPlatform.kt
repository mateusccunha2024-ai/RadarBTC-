package com.cunhac.btcradar.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.lang.management.ManagementFactory

/** Imagem a partir de pixels ARGB (heatmap). */
fun argbImage(width: Int, height: Int, argb: IntArray): ImageBitmap {
    val bytes = ByteArray(width * height * 4)
    for (i in argb.indices) {
        val c = argb[i]
        bytes[i * 4] = (c and 0xFF).toByte(); bytes[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
        bytes[i * 4 + 2] = (c shr 16 and 0xFF).toByte(); bytes[i * 4 + 3] = (c ushr 24).toByte()
    }
    return Image.makeRaster(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL), bytes, width * 4).toComposeImageBitmap()
}

/** Uso de recursos do processo (aba STATUS). */
fun processStats(): List<Pair<String, String>> {
    val rt = Runtime.getRuntime()
    val cpu = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.processCpuLoad?.let { it * 100 }
    return listOf(
        "CPU do processo" to (cpu?.let { num(it, 1) + " %" } ?: "—"),
        "Memória (usada / máx.)" to "${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} / ${rt.maxMemory() / 1_048_576} MB",
        "Threads" to Thread.activeCount().toString(),
    )
}

object Platform {
    const val isMobile = false
    /** Notificação do Windows pela bandeja do sistema. */
    val notifySupported: Boolean get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)
    private val tray: TrayIcon? by lazy {
        runCatching {
            val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
                createGraphics().apply { color = java.awt.Color(0xF7, 0x93, 0x1A); fillOval(1, 1, 14, 14); dispose() }
            }
            TrayIcon(img, "Radar BTC").also { it.isImageAutoSize = true; SystemTray.getSystemTray().add(it) }
        }.getOrNull()
    }
    fun notify(title: String, text: String): Boolean {
        val t = tray ?: return false
        t.displayMessage(title, text, TrayIcon.MessageType.INFO)
        return true
    }
    /** No Windows o app já roda enquanto a janela estiver aberta. */
    const val backgroundSupported = false
    val backgroundOn: Boolean get() = false
    fun setBackground(on: Boolean) {}
    val deviceLabel = "Windows"

    /** Build instalado: o jpackage grava a versão 1.0.N do instalador; N = número do build (0 fora do app instalado). */
    fun buildNumber(): Int = System.getProperty("jpackage.app-version")?.substringAfterLast('.')?.toIntOrNull() ?: 0
    fun updateDir(): java.io.File = java.io.File(System.getProperty("java.io.tmpdir"), "RadarBTC-update")
    /** Instala o .msi novo (por usuário, sem admin) só com barra de progresso, reabre o app e fecha este processo. */
    fun installUpdate(msi: java.io.File) {
        val exe = System.getProperty("jpackage.app-path")
        val q = '"'
        val bat = java.io.File(updateDir(), "atualizar.cmd")
        val lines = mutableListOf("@echo off", "timeout /t 2 /nobreak >nul", "msiexec /i $q${msi.absolutePath}$q /passive /norestart")
        if (exe != null) lines += "start $q$q $q$exe$q"
        bat.writeText(lines.joinToString("\r\n") + "\r\n")
        ProcessBuilder("cmd.exe", "/c", bat.absolutePath).start()
        kotlin.system.exitProcess(0)
    }
}
