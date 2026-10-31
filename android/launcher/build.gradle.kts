plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versão e chave vêm do CI (ver android/README.md): -PrumoVersionName=1.2.3 -PrumoVersionCode=1234 e as variáveis
// RUMO_KEYSTORE, RUMO_KEYSTORE_PASSWORD, RUMO_KEY_ALIAS e RUMO_KEY_PASSWORD. Sem elas (build local), a versão é
// "0.0.0-dev" e o APK sai assinado com a chave de depuração do próprio Android SDK.
val rumoVersionName = providers.gradleProperty("rumoVersionName").orElse("0.0.0-dev").get()
val rumoVersionCode = providers.gradleProperty("rumoVersionCode").map(String::toInt).orElse(1).get()
val rumoKeystore = System.getenv("RUMO_KEYSTORE")?.let(::file)?.takeIf { it.isFile }

android {
    namespace = "app.rumo"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.rumo"
        // O aparelho de referência é Android 9 (API 28), embora exiba "15"
        minSdk = 26
        targetSdk = 34
        versionCode = rumoVersionCode
        versionName = rumoVersionName
        // Só o processador do aparelho (32 bits): o APK com o MapLibre fica bem menor
        ndk { abiFilters += "armeabi-v7a" }
    }

    // Chave fixa do projeto (guardada como segredo do CI): as versões novas instalam por cima das antigas
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
            signingConfigs.findByName("rumo")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Só foundation (sem Material): o visual é próprio e o aparelho é modesto
    val compose = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    // Mapa vetorial offline (PMTiles desde a 11.7). A linha 11 usa OpenGL ES 3 (o Mali-T720 tem 3.1);
    // a 13 passou a usar Vulkan por padrão.
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    testImplementation("junit:junit:4.13.2")
}
