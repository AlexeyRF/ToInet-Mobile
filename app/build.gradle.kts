import com.android.build.api.dsl.ApplicationExtension
import java.io.FileInputStream
import java.util.*

plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.application)
}

kotlin { jvmToolchain(21) }

val ToInetBaseVersionCode = 12

// Provider for git version, configuration-cache safe
val gitVersionProvider = providers.exec {
    commandLine("git", "describe", "--tags", "--always")
}.standardOutput.asText.map { it.trim() }

val runNdkBuild = tasks.register<Exec>("runNdkBuild") {
    group = "build"
    
    args(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        "NDK_LIBS_OUT=src/main/jniLibs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk"
    )
}

tasks.named("preBuild") {
    dependsOn(runNdkBuild)
}

configure<ApplicationExtension> {
    namespace = "ru.toinet.android"
    compileSdk = 36

    defaultConfig {
        applicationId = namespace
        versionCode = ToInetBaseVersionCode
        versionName = "PMR"
        minSdk = 24
        targetSdk = 36
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        flavorDimensions += "free"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    splits {
        abi {
            isEnable = false
            reset()
            include("x86", "armeabi-v7a", "x86_64", "arm64-v8a")
            isUniversalApk = true
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions { execution = "ANDROIDX_TEST_ORCHESTRATOR" }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.canRead()) {
                keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
            }
            if (keystoreProperties.isNotEmpty()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.txt"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    productFlavors {
        create("fullperm") {
            dimension = "free"
        }
        create("nightly") {
            dimension = "free"
            // overwrites defaults from defaultConfig
            applicationId = "ru.toinet.android.nightly"
            versionCode = (Date().time / 1000).toInt()
        }
    }

    packaging {
        resources {
            excludes += listOf("META-INF/androidx.localbroadcastmanager_localbroadcastmanager.version")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "InvalidPackage"
        htmlReport = true
        lintConfig = file("../lint.xml")
        textReport = false
        xmlReport = false
    }

    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
        }
    }

    // Configure runNdkBuild using captured NDK info
    val currentNdkPath = ndkPath
    val currentNdkVersion = ndkVersion

    runNdkBuild.configure {
        doFirst {
            var resolvedNdkDir = currentNdkPath
            if (resolvedNdkDir == null) {
                val localProperties = File(project.rootDir, "local.properties")
                val sdkDir = if (localProperties.exists()) {
                    val properties = Properties()
                    localProperties.inputStream().use { properties.load(it) }
                    properties.getProperty("sdk.dir")
                } else {
                    System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
                }
                if (sdkDir != null) {
                    resolvedNdkDir = "$sdkDir/ndk/$currentNdkVersion"
                }
            }

            if (resolvedNdkDir != null) {
                executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    "$resolvedNdkDir\\ndk-build.cmd"
                } else {
                    "$resolvedNdkDir/ndk-build"
                }
            }
        }
        doLast {
            val jniLibsDir = file("src/main/jniLibs")
            listOf("x86", "armeabi-v7a", "x86_64", "arm64-v8a").forEach { abi ->
                val abiDir = File(jniLibsDir, abi)
                listOf("pdnsd", "tun2socks").forEach { binName ->
                    val binFile = File(abiDir, binName)
                    if (binFile.exists()) {
                        binFile.renameTo(File(abiDir, "lib${binName}.so"))
                    }
                }
            }
        }
    }
}

val copyLicenseToAssets by tasks.registering(Copy::class) {
    from(rootProject.file("LICENSE"))
    into(layout.projectDirectory.dir("src/main/assets"))
}

// Increments versionCode by ABI type and handles tasks/outputs
androidComponents {
    onVariants { variant ->
        // Handle versionCode and output file naming
        variant.outputs.forEach { output ->
            // Update versionCode lazily
            if (output.versionCode.get() == ToInetBaseVersionCode) {
                val incrementMap = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 4, "x86_64" to 5)
                val abi = output.filters.find { it.filterType.name == "ABI" }?.identifier
                val increment = incrementMap[abi] ?: 0
                output.versionCode.set(ToInetBaseVersionCode + increment)
            }
            
            // Modern APK renaming using outputFileName property
            output.outputFileName.set("Toinet.apk")
        }
    }
}

// Safer way to depend on variant-specific tasks that are created lazily by AGP
tasks.matching { 
    it.name == "preFullpermReleaseBuild" || it.name == "preNightlyReleaseBuild" 
}.configureEach {
    dependsOn(copyLicenseToAssets)
}

dependencies {
    implementation(libs.android.material)
    implementation(libs.android.volley)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.localbroadcast)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.process)
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation(libs.retrofit.converter)
    implementation(libs.retrofit.lib)
    implementation(libs.rootbeer.lib)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.kotlin)
    implementation(libs.upnp)
    implementation(libs.iptproxy)

    // Tor
    implementation(files("../libs/geoip.jar"))
    api(libs.guardian.jtorctl)
    api(libs.tor.android)

    testImplementation(libs.junit.jupiter)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.rules)
    androidTestImplementation(libs.androidx.runner)
    androidTestImplementation(libs.androidx.espresso.contrib)
    androidTestUtil(libs.androidx.orchestrator)
}
