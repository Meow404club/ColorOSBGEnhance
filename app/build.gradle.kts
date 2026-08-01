plugins {
    id("com.android.application")
}

android {
    namespace = "com.frostwork.bgenhance"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.frostwork.bgenhance"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    compileOnly("de.robv.android.xposed:api:82")
}
