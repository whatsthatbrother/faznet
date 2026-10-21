plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.faznet.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.faznet.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"

        // MAC/PIN web upload: API (Cloudflare Worker or the Flask server in /server) and the public website.
        val server = (project.findProperty("faznetServer") as String?) ?: "https://faznet.diarfazliu.workers.dev"
        val web = (project.findProperty("faznetWeb") as String?) ?: "https://whatsthatbrother.github.io/faznet"
        buildConfigField("String", "SERVER_URL", "\"$server\"")
        buildConfigField("String", "WEB_URL", "\"$web\"")
    }

    // The release key is NOT in the repository. Keep keystore/faznet.jks + keystore/keystore.properties locally
    // (storePassword=..., keyPassword=...) or provide FAZNET_STORE_PASSWORD / FAZNET_KEY_PASSWORD.
    val keyProps = java.util.Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/faznet.jks")
            storePassword = keyProps.getProperty("storePassword") ?: System.getenv("FAZNET_STORE_PASSWORD")
            keyAlias = "faznet"
            keyPassword = keyProps.getProperty("keyPassword") ?: System.getenv("FAZNET_KEY_PASSWORD")
        }
    }

    // Android Studio can only be driven by clicks here, so a `release.flag` file in the project root turns
    // the default (debug) variant into a real release: R8, release signing, no debugging, no test assets.
    val releaseFlag = rootProject.file("release.flag").exists()

    buildTypes {
        debug {
            if (releaseFlag) {
                isDebuggable = rootProject.file("debuggable.flag").let { it.exists() && it.readText().trim() == "1" }
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    if (releaseFlag) {
        sourceSets.getByName("debug").assets.setSrcDirs(emptyList<String>())
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.9.0"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    // FFmpeg audio decoder (AC3, E-AC3, MP2, DTS...) built by Jellyfin against the same Media3 version
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("io.coil-kt:coil:2.7.0")
}
