plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.automusicshorts.acquisitionsmoke"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.automusicshorts.acquisitionsmoke.audio"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-mobile-jobs"
        buildConfigField("String", "BUILD_COMMIT", "\"${System.getenv("GITHUB_SHA")?.take(12) ?: "local"}\"")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    testImplementation("junit:junit:4.13.2")
}
