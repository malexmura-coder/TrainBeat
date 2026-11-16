import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Where the signing key is, read from local.properties, which git ignores.
 *
 * Absent everywhere but this machine, and that is the point. F-Droid builds this
 * same file on their own server and signs the result with their own key, so there
 * the properties are missing, no signing config is attached, and assembleRelease
 * produces the unsigned APK they expect. A signing config that named a keystore
 * which is not there would fail their build rather than be ignored by it.
 *
 * Nothing about the key lives in the repository: not the password, and not even
 * the path to it. A secret committed once is committed for good - removing it
 * later leaves it in the history, where it is still readable.
 */
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val keystorePath: String = signingProps.getProperty("TRAINBEAT_STORE_FILE") ?: ""

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
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        if (keystorePath.isNotEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                // PKCS12 keeps one password for the store and for the key
                storePassword = signingProps.getProperty("TRAINBEAT_STORE_PASSWORD")
                keyAlias = signingProps.getProperty("TRAINBEAT_KEY_ALIAS")
                keyPassword = signingProps.getProperty("TRAINBEAT_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
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
