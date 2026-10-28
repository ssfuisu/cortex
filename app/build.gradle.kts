plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.cortex.terminal"
    compileSdk = 34

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "org.cortex.terminal"
        minSdk = 24
        // Target SDK 28 is required to allow direct execve of binaries and glibc dynamic linker
        // from the app's writable data directory (filesDir). Android 10+ (targetSdk >= 29)
        // enforces SELinux W^X restrictions preventing execution from writable app data storage.
        targetSdk = 28
        versionCode = 12645
        versionName = "1.26.45"
        // UbuntuOnDeviceTest (app/src/androidTest), on Firebase Test Lab
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments("-DANDROID_STL=none")
                cFlags("-std=c11", "-Wall", "-O3")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    androidResources {
        // zst: the bootstrap archive, read by the native extractor as it is
        noCompress += listOf("gz", "tgz", "gpg", "zst")
    }

    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val resolvedCustomKeystore = if (!releaseStoreFile.isNullOrEmpty()) {
        val candidate = file(releaseStoreFile)
        if (candidate.exists()) candidate else rootProject.file(releaseStoreFile)
    } else {
        null
    }
    // The release key is never read from the repository (it used to be committed together with its
    // password). It comes only from the environment, which CI fills from repository secrets.
    val (activeKeystore, activeStorePass, activeAlias, activeKeyPass) = when {
        resolvedCustomKeystore != null &&
            resolvedCustomKeystore.exists() &&
            !releaseStorePassword.isNullOrEmpty() &&
            !releaseKeyAlias.isNullOrEmpty() &&
            !releaseKeyPassword.isNullOrEmpty() -> {
            listOf(resolvedCustomKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
        }
        else -> {
            listOf(null, null, null, null)
        }
    }
    val hasReleaseSigning = activeKeystore != null

    val isReleaseTaskRequested = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("Release", ignoreCase = true) &&
            (taskName.contains("assemble", ignoreCase = true) ||
                taskName.contains("bundle", ignoreCase = true) ||
                taskName.contains("package", ignoreCase = true))
    }
    if (isReleaseTaskRequested && !hasReleaseSigning) {
        throw GradleException(
            "Release build requires the release signing environment variables (RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD)."
        )
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning && activeKeystore != null) {
                storeFile = activeKeystore as java.io.File
                storePassword = activeStorePass as String
                keyAlias = activeAlias as String
                keyPassword = activeKeyPass as String
            } else {
                initWith(getByName("debug"))
            }
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
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric tests (BootstrapManagerFlowTest) read the app's assets
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                // The assertion's message in CI's log, not only in the HTML report
                it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    lint {
        checkReleaseBuilds = true
        abortOnError = true
        disable.addAll(listOf("ExpiredTargetSdkVersion"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
