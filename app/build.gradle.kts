plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.automusicshorts.acquisitionsmoke"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.automusicshorts.acquisitionsmoke.audio.e2eqa"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-e2e-qa"
        buildConfigField("String", "BUILD_COMMIT", "\"${System.getenv("GITHUB_SHA")?.take(12) ?: "local"}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Music Shorts E2E QA"
    }

    flavorDimensions += "qa"
    productFlavors {
        create("device") {
            dimension = "qa"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("automation") {
            dimension = "qa"
            applicationIdSuffix = ".automation"
            versionNameSuffix = "-automation"
            manifestPlaceholders["appLabel"] = "Music Shorts Automation QA"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
