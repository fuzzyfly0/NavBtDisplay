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
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // AutoMediaService only shows the navigation hint; there is nothing to search or play.
        disable += setOf("MissingIntentFilterForMediaSearch", "MissingOnPlayFromSearch")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Android Auto screen with the current hint (CarHintService).
    implementation("androidx.car.app:app:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
