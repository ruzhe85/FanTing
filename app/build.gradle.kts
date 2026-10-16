plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
val translationAssets = tasks.register<Sync>("prepareTranslationAssets") {
    from("src/main/res") { include("values*/strings.xml") }
    into(layout.buildDirectory.dir("generated/translationAssets/translations"))
}

// 签名：keystore 不入库。CI 用 Secrets 解码出 keystore/omnibeatp.jks 并注入下面几个环境变量；
// 本地没有 keystore 时自动跳过签名，不影响编译与 debug 构建。
val signingKeystore = file(System.getenv("OMNIBEATP_KEYSTORE") ?: "keystore/omnibeatp.jks")
val signingEnabled =
    signingKeystore.exists() && !System.getenv("OMNIBEATP_KEYSTORE_PASSWORD").isNullOrBlank()

android {
    namespace = "omnibeat.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 与上游官方版（omnibeat.app）不同的 applicationId，以便与官方版共存安装。
        // namespace 保持 omnibeat.app 不变，源码包名与上游一致，便于持续同步上游。
        applicationId = "app.ruzhe.omnibeatp"
        minSdk = 34
        targetSdk = 37
        versionCode = providers.gradleProperty("app.versionCode").get().toInt()
        versionName = providers.gradleProperty("app.versionName").get()

        ndk {
            debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
        create("github") {
            dimension = "distribution"
            isDefault = true
        }
    }
    signingConfigs {
        if (signingEnabled) {
            create("release") {
                storeFile = signingKeystore
                storePassword = System.getenv("OMNIBEATP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OMNIBEATP_KEY_ALIAS") ?: "omnibeatp"
                keyPassword = System.getenv("OMNIBEATP_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (signingEnabled) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        generateLocaleConfig = true
    }
    sourceSets.getByName("main").assets.directories.add(
        layout.buildDirectory.dir("generated/translationAssets").get().asFile.absolutePath,
    )
}

tasks.named("preBuild").configure { dependsOn(translationAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.reorderable)
    debugImplementation(libs.compose.ui.tooling)
}
