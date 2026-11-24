plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.phoshdroid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phoshdroid.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 13
        versionName = "0.0.13-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        noCompress += listOf("zst", "gz", "tar", "bin")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release with the debug keystore so `gradlew assembleRelease`
            // produces an installable APK out of the box. Replace with a real
            // signing config before publishing to a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        // Lint crashes on the heavy Termux submodule sources (upstream warnings
        // that aren't ours to fix) and on its own NonNullableMutableLiveData
        // detector (known AGP/Kotlin IncompatibleClassChangeError). Don't abort
        // on either; still produce the report.
        abortOnError = false
        checkReleaseBuilds = false
        disable += listOf("NullSafeMutableLiveData", "SuspiciousIndentation")
        // Only lint our own sources, not the transitive submodule outputs.
        checkDependencies = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The guest scripts travel with the APK (R3 H13). Keep this list identical to the
// "Applying overlay" block in rootfs/package-omarchy-rootfs.sh: the same five
// scripts plus fake-proc/, installed into the guest's /etc/phoshdroid/.
// GuestOverlay.sync then rewrites whatever differs under
// <prefix>/var/lib/proot-distro/installed-rootfs/omarchy before a session starts,
// so an APK update reaches an existing install without wiping app data.
val guestOverlayAssetDir = layout.buildDirectory.dir("generated/guestOverlay/guest-overlay")

val syncGuestOverlay =
    tasks.register<Sync>("syncGuestOverlay") {
        from(rootProject.file("rootfs/overlay/etc/phoshdroid")) {
            include("start.sh", "session-child.sh", "omarchy-plugin-index.sh", "fit-x11.py", "omarchy-watch.py")
            include("fake-proc/**")
        }
        into(guestOverlayAssetDir.map { it.dir("etc/phoshdroid") })
    }

// The registered dir is an asset ROOT, so it is the generated tree's parent: the
// APK then carries assets/guest-overlay/etc/phoshdroid/..., the path
// GuestOverlay.ASSET_ROOT reads. Every task that reads the assets (each variant's
// merge/package, and lint's report model) must wait for the copy, or Gradle
// flags an implicit dependency on the task's output.
val guestOverlayAssetsRoot = layout.buildDirectory.dir("generated/guestOverlay")
android.sourceSets.getByName("main").assets.srcDir(guestOverlayAssetsRoot.get().asFile.absolutePath)

tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        (it.name.startsWith("package") && it.name.endsWith("Assets")) ||
        it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(syncGuestOverlay)
}

// guava:24.1-jre (pulled in by termux-shared) and androidx.* both bring ListenableFuture.
// Replace the standalone artifact with the empty conflict-avoidance stub so guava wins.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.google.guava" && requested.name == "listenablefuture") {
            useVersion("9999.0-empty-to-avoid-conflict-with-guava")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Termux modules
    implementation(project(":terminal-emulator"))
    implementation(project(":termux-shared"))
    // termux-x11-app converted to library module so we can embed it in the same APK
    implementation(project(":termux-x11-app"))

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
