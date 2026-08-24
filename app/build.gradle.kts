plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.malexmuracoder.trainbeat"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.malexmuracoder.trainbeat"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Unit tests run on a desktop JVM: no phone, no emulator.
// The system properties feed the render and library-scan tools.
tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
    }
    // run from the project root, so the tools find scan-folder.txt next to gradlew
    workingDir = rootDir
    systemProperty("looper.wav", providers.systemProperty("looper.wav").getOrElse(""))
    systemProperty("trainbeat.dir", providers.systemProperty("trainbeat.dir").getOrElse(""))
    systemProperty("looper.loops", providers.systemProperty("looper.loops").getOrElse("3"))
    systemProperty(
        "looper.outdir",
        providers.systemProperty("looper.outdir")
            .getOrElse(layout.buildDirectory.dir("loop-render").get().asFile.absolutePath)
    )
}
