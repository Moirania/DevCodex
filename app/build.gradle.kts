import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    lint {
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
    }

    namespace = "ai.devcodex.agent"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ai.devcodex.agent"
        minSdk = 26
        targetSdk = 28
        versionCode = 85
        versionName = "0.3.2"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("fixedDebug") {
            storeFile = file("devcodex-debug.p12")
            storeType = "pkcs12"
            storePassword = "devcodex-debug"
            keyAlias = "devcodex"
            keyPassword = "devcodex-debug"
        }
        create("release") {
            val ksPath = System.getenv("DC_KEYSTORE_PATH")
            val ksPwd = System.getenv("DC_KEYSTORE_PASSWORD") ?: ""
            val kAlias = System.getenv("DC_KEY_ALIAS") ?: "devcodex"
            val kPwd = System.getenv("DC_KEY_PASSWORD") ?: ""
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = ksPwd
                keyAlias = kAlias
                keyPassword = kPwd
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // M3L 渲染库（Markdown+TextMate 高亮+LaTeX）
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:image-coil:4.6.2")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.ivan-magda:kotlin-textmate-core:0.3.0")
    implementation("com.github.mwiede:jsch:0.2.18")
}

tasks.matching { it.name == "compileReleaseArtProfile" }.configureEach {
    enabled = false
}
