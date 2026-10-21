plugins {
    id("com.android.application")
}

android {
    namespace = "de.feineflammen.rotg02viewer"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "de.feineflammen.rotg02viewer"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Test-/Direktinstallationsbuild: nicht debuggable, aber installierbar.
            // Für öffentliche Verteilung später durch einen dauerhaft geheim
            // gehaltenen Release-Key ersetzen.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
