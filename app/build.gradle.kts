plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Cada build do GitHub ganha um número maior, para o Android aceitar como atualização
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
// Assinatura permanente (vem dos secrets do repositório; sem ela, usa a de debug)
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_FILE")

android {
    namespace = "com.cunhac.btcradar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cunhac.btcradar"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        // Bibliotecas nativas do assistente (IA e voz): celulares de 64 e 32 bits e emulador x86_64.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    signingConfigs {
        create("radar") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) signingConfigs.getByName("radar") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Telas do terminal compartilhadas com o app do Windows
    sourceSets["main"].java.srcDir("../terminal/src/main/kotlin")
    sourceSets["main"].resources.srcDir("../terminal/src/main/resources")   // traduções (i18n/*.tsv)
    // O lint de release não deve impedir a geração do APK (avisos ficam no relatório)
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging {
        resources {
            pickFirsts += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt",
                "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/DEPENDENCIES", "META-INF/FastDoubleParser-LICENSE", "META-INF/FastDoubleParser-NOTICE")
            excludes += setOf("META-INF/versions/**/module-info.class", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
    }
}

// Assistente (Jarvis): voz e microfone locais com sherpa-onnx (AAR oficial das releases do GitHub; baixado uma vez para .deps/).
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
val sherpaAar = fetchDep("sherpa-onnx-$sherpaVersion.aar", "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")

dependencies {
    // Assistente: IA local (llama.cpp para Android, exige Android 9+ em tempo de execução), voz/microfone e extração dos modelos
    implementation("net.ladenthin:llama-android:6.0.0")
    implementation(files(sherpaAar))
    implementation("org.apache.commons:commons-compress:1.27.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    // Alertas por e-mail (SMTP), os mesmos do Windows
    implementation("jakarta.mail:jakarta.mail-api:2.1.3")
    implementation("org.eclipse.angus:angus-mail:2.0.3")
    implementation("org.eclipse.angus:angus-activation:2.0.2")

    testImplementation("junit:junit:4.13.2")
}
