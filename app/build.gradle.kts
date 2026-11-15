plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "app.samara.cameras"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.samara.cameras.native"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
}