import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// Reaproveita do app Android: dados das corretoras, modelo de liquidez, tema e componentes visuais
val shared = "../app/src/main/java/com/cunhac/btcradar"
kotlin {
    sourceSets.named("main") {
        kotlin.srcDir("$shared/data")
        kotlin.srcDir("$shared/domain")
        kotlin.srcDir("$shared/ui/theme")
        kotlin.srcDir("$shared/ui/components")
        kotlin.srcDir("$shared/engine")
        kotlin.srcDir("../terminal/src/main/kotlin")
        resources.srcDir("../terminal/src/main/resources")   // traduções (i18n/*.tsv)
    }
}

// Assistente (Jarvis): voz e microfone locais com sherpa-onnx. Os jars oficiais saem das releases do GitHub
// (não estão no Maven Central); baixados uma vez para .deps/.
val sherpaVersion = "2.0.3"
fun fetchDep(name: String, url: String): File {
    val f = rootProject.file(".deps/$name")
    if (!f.exists() || f.length() == 0L) {
        f.parentFile.mkdirs()
        val tmp = File(f.parentFile, "$name.part")
        uri(url).toURL().openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (!tmp.renameTo(f)) error("não foi possível salvar $name")
    }
    return f
}
val osName = System.getProperty("os.name").lowercase()
val sherpaNative = when {
    osName.contains("win") -> "win-x64"
    osName.contains("mac") -> if (System.getProperty("os.arch").contains("aarch64")) "osx-aarch64" else "osx-x64"
    else -> if (System.getProperty("os.arch").contains("aarch64")) "linux-aarch64" else "linux-x64"
}
val sherpaBase = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion"
val sherpaJars = files(
    fetchDep("sherpa-onnx-jvm-$sherpaVersion.jar", "$sherpaBase/sherpa-onnx-jvm-$sherpaVersion.jar"),
    fetchDep("sherpa-onnx-native-lib-$sherpaNative-$sherpaVersion.jar", "$sherpaBase/sherpa-onnx-native-lib-$sherpaNative-$sherpaVersion.jar"),
)

dependencies {
    implementation(compose.desktop.currentOs)
    // Assistente: IA local (llama.cpp), voz/microfone (sherpa-onnx) e extração dos modelos (.tar.bz2)
    implementation("de.kherud:llama:4.2.0")
    implementation(sherpaJars)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    // Alertas por e-mail (SMTP)
    implementation("jakarta.mail:jakarta.mail-api:2.1.3")
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    implementation("org.eclipse.angus:angus-activation:2.0.2")

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER") ?: "0"

compose.desktop {
    application {
        mainClass = "com.cunhac.btcradar.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "RadarBTC"
            packageVersion = "1.0.$runNumber"
            description = "Mapa de liquidez, liquidacoes e ETFs de Bitcoin"
            vendor = "Cunhac"
            includeAllModules = true
            windows {
                menu = true
                shortcut = true
                perUserInstall = true
                menuGroup = "Radar BTC"
                upgradeUuid = "6f1c2a7e-3b9d-4e52-9a71-2c8d5b4e1f03"
            }
        }
    }
}
