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
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
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
