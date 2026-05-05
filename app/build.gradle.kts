plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

fun projectString(name: String): String? {
  return (findProperty(name) as String?) ?: System.getenv(name)
}

android {
  namespace = "com.freeflowtv.app"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.freeflowtv.app"
    minSdk = 23
    targetSdk = 34
    versionCode = 9
    versionName = "1.0.8"
  }

  signingConfigs {
    val storeFilePath = projectString("FREEFLOWTV_UPLOAD_STORE_FILE")
    val storePasswordValue = projectString("FREEFLOWTV_UPLOAD_STORE_PASSWORD")
    val keyAliasValue = projectString("FREEFLOWTV_UPLOAD_KEY_ALIAS")
    val keyPasswordValue = projectString("FREEFLOWTV_UPLOAD_KEY_PASSWORD")

    if (
      !storeFilePath.isNullOrBlank() &&
      !storePasswordValue.isNullOrBlank() &&
      !keyAliasValue.isNullOrBlank() &&
      !keyPasswordValue.isNullOrBlank()
    ) {
      create("release") {
        storeFile = file(storeFilePath)
        storePassword = storePasswordValue
        keyAlias = keyAliasValue
        keyPassword = keyPasswordValue
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      signingConfig = signingConfigs.findByName("release")
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
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
    viewBinding = true
  }
}

dependencies {
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
  implementation("androidx.media3:media3-exoplayer:1.4.1")
  implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
  implementation("androidx.media3:media3-ui:1.4.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  testImplementation("junit:junit:4.13.2")
}
