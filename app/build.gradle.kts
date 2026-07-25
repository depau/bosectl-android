plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "eu.depau.bosectl"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.depau.bosectl"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    }

    // The BMAP protocol probe lives in its own flavor so it ships in nothing by
    // default — not even debug builds. Build it explicitly when reverse
    // engineering: ./gradlew installProbeDebug   (see docs/PROTOCOL.md)
    flavorDimensions += "tools"
    productFlavors {
        create("standard") { dimension = "tools" }
        create("probe") { dimension = "tools" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// A probe release build makes no sense; keep the variant list small.
androidComponents {
    beforeVariants(selector().withFlavor("tools" to "probe").withBuildType("release")) {
        it.enable = false
    }
}

dependencies {
    implementation(project(":bmap"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
