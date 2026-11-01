import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// `gradlew assembleDebug -Pbench` builds a second app next to the real one (see tools/README.md): same code and
// same R8 settings, but its own data, no launcher icon, no server. For measuring only, it is never published.
val bench = project.hasProperty("bench")
if (bench) layout.buildDirectory.set(file("build-bench"))

// The release key is NOT in the repository (see signingConfigs below).
val keyProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.faznet.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = if (bench) "com.faznet.tv.bench" else "com.faznet.tv"
        minSdk = 23
        targetSdk = 35
        // The bench app must never be offered an update from the website.
        versionCode = if (bench) 999_999 else 43
        versionName = "4.0.3"

        // MAC/PIN web upload: API (Cloudflare Worker or the Flask server in /server) and the public website.
        // "REPLACE" counts as no server (Repository.serverUrl): the bench app registers nothing and sends no reports.
        val server = if (bench) "REPLACE" else (project.findProperty("faznetServer") as String?) ?: "https://faznet.diarfazliu.workers.dev"
        val web = (project.findProperty("faznetWeb") as String?) ?: "https://whatsthatbrother.github.io/faznet"
        buildConfigField("String", "SERVER_URL", "\"$server\"")
        buildConfigField("String", "WEB_URL", "\"$web\"")

        // -PplayerTrace: the first seconds of each channel in logcat, for hunting freezes (see player/PlayerTrace.kt).
        // -PplayerTrace=watch also stops the quick re-open, to see what a stream does by itself. Never in a release.
        val trace = project.findProperty("playerTrace")?.toString()
        buildConfigField("boolean", "PLAYER_TRACE", (trace != null).toString())
        buildConfigField("boolean", "PLAYER_TRACE_WATCH", (trace == "watch").toString())
    }

    // The release key is NOT in the repository. Keep keystore/faznet.jks + keystore/keystore.properties locally
    // (storePassword=..., keyPassword=...) or provide FAZNET_STORE_PASSWORD / FAZNET_KEY_PASSWORD.
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
                signingConfig = signingConfigs.getByName(if (bench) "debug" else "release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // One APK per processor type (VLC is big): armeabi-v7a (~25 MB) for TV sticks such as the Mi TV Stick,
    // arm64-v8a for newer 64-bit-only devices.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
        }
    }

    // Compress native libraries (VLC is ~85 MB uncompressed): the APK stays around 40 MB instead of 100 MB.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (releaseFlag) {
        sourceSets.getByName("debug").assets.directories.clear()
    }
    if (bench) {
        sourceSets.getByName("debug") {
            kotlin.directories.add("src/bench/java")
            manifest.srcFile("src/bench/AndroidManifest.xml")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    // The database schema of each version is kept in the repository, for writing and testing migrations.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val media3 = "1.9.0"
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
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
    // Installs the ahead-of-time (baseline) profiles also for APKs installed outside Google Play
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Second player engine (Settings → VLC): tolerant of badly started / damaged IPTV streams
    implementation("org.videolan.android:libvlc-all:3.7.6")
    // 2.0: the screens are drawn with Jetpack Compose (made for the TV remote and for touch)
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")

    val room = "2.8.5"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    val paging = "3.5.3"
    implementation("androidx.paging:paging-runtime:$paging")
    implementation("androidx.paging:paging-compose:$paging")
    val hilt = "2.60.1"
    implementation("com.google.dagger:hilt-android:$hilt")
    ksp("com.google.dagger:hilt-compiler:$hilt")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
