plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "at.websters.tabbyandroid"
    // Android 16 (API 36) - RedMagic 10 Pro ready. 16KB page-size compatible
    // (pure Kotlin/Java, no legacy native libs). Edge-to-edge enforced.
    compileSdk = 36

    defaultConfig {
        applicationId = "at.websters.tabbyandroid"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // Local keystore in ~/.android/tabby-keys (never in git);
            // passwords come from ~/.gradle/gradle.properties or env.
            val home = System.getProperty("user.home")
            signingConfigs.create("release") {
                storeFile = file("$home/.android/tabby-keys/tabby-release.jks")
                storePassword = project.findProperty("TABBY_RELEASE_STORE_PASSWORD") as String?
                    ?: System.getenv("TABBY_RELEASE_STORE_PASSWORD")
                // PKCS12 keystores use the store password for the key as well.
                keyPassword = storePassword
                keyAlias = "tabby"
            }
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
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
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Biometric / device-credential prompts + FragmentActivity for them
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")

    // DataStore (accounts + cached profiles, no Room/KSP needed for v1)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Encrypted token storage (best practice for sync tokens + key passphrases)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking - Tabby sync API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // YAML parsing for Tabby config.yaml content
    implementation("org.yaml:snakeyaml:2.3")

    // SSH - maintained JSch fork (BSD-style license, password + publickey + keyboard-interactive + Ed25519)
    implementation("com.github.mwiede:jsch:0.2.21")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.datastore:datastore-preferences:1.1.1")
}
