plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.fuzzyfly0.navbtdisplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.fuzzyfly0.navbtdisplay"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
