plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.keelcat.mobile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.keelcat.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // KeelCat: embedded web server (serves the same web UI + /api on-device),
    // on-device LLM, GitHub networking, coroutines, lifecycle, WebView.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.mediapipe:tasks-genai:0.10.14")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.11.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}