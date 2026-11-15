plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// App do rádio online do Rumo: só o player, sem tela. Fica à parte do launcher porque o firmware encerra o pacote que
// tocou mídia quando outra fonte pega o som (ver docs/device-integration.md). Versão e chave são as mesmas do Rumo.
val rumoVersionName = providers.gradleProperty("rumoVersionName").orElse("0.0.0-dev").get()
val rumoVersionCode = providers.gradleProperty("rumoVersionCode").map(String::toInt).orElse(1).get()
val rumoKeystore = System.getenv("RUMO_KEYSTORE")?.let(::file)?.takeIf { it.isFile }

android {
    namespace = "app.rumo.webradio"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.rumo.webradio"
        minSdk = 26
        targetSdk = 34
        versionCode = rumoVersionCode
        versionName = rumoVersionName
    }

    signingConfigs {
        if (rumoKeystore != null) {
            create("rumo") {
                storeFile = rumoKeystore
                storePassword = System.getenv("RUMO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RUMO_KEY_ALIAS")
                keyPassword = System.getenv("RUMO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("rumo")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("rumo") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    // O protocolo com o Rumo (android/shared) entra nos dois apps
    sourceSets["main"].java.srcDir("../shared")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Player de streaming (MP3/AAC e HLS)
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
}
