plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseKeystorePath = providers.environmentVariable("GXZ_SIGNING_KEYSTORE_PATH").orNull

android {
    namespace = "io.github.tbeimu.gongxiaozhu"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.tbeimu.gongxiaozhu"
        minSdk = 31
        targetSdk = 36
        versionCode = 15
        versionName = "0.3.3"
        val releaseCertificate = rootProject.file("release-signing.sha256").readText().trim().lowercase()
        require(Regex("[a-f0-9]{64}").matches(releaseCertificate)) { "正式签名摘要格式错误" }
        buildConfigField("String", "RELEASE_CERT_SHA256", "\"$releaseCertificate\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (!releaseKeystorePath.isNullOrBlank()) {
            create("production") {
                storeFile = file(releaseKeystorePath)
                storeType = "PKCS12"
                storePassword = providers.environmentVariable("GXZ_SIGNING_STORE_PASSWORD").orNull
                    ?: error("缺少正式签名存储口令")
                keyAlias = providers.environmentVariable("GXZ_SIGNING_KEY_ALIAS").orNull
                    ?: error("缺少正式签名别名")
                keyPassword = providers.environmentVariable("GXZ_SIGNING_KEY_PASSWORD").orNull
                    ?: error("缺少正式签名密钥口令")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("production")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        warningsAsErrors = true
        // v0.1.0 pins targetSdk 36. New target SDKs change runtime behavior and
        // require separate compatibility testing; retain this upgrade advisory
        // without making CI depend on the runner's installed SDK inventory.
        informational += "OldTargetApi"
        // Versions are intentionally pinned as a compatible set. Upgrade advisories
        // are reviewed separately; all correctness, security and accessibility checks remain enabled.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("androidx.webkit:webkit:1.14.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.11.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
