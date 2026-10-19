plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.kimi.casttv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kimi.casttv"
        minSdk = 24
        targetSdk = 34
        versionCode = 21
        versionName = "2.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Media3 ExoPlayer (播放投屏视频，支持 mp4 / HLS / DASH 等)
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    // RTMP 数据源：看球直播等 App 投屏的是 rtmp:// 直播流，ExoPlayer 核心不支持，需此扩展
    implementation("androidx.media3:media3-datasource-rtmp:$media3")
}
