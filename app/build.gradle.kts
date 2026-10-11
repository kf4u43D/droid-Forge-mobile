plugins { id("com.android.application") }

// Read secrets from the environment or the user's external ~/.gradle/gradle.properties.
// No production key is generated and unsigned release builds remain available for review.
val releaseSigning = listOf(
    "ANDROFORGE_KEYSTORE" to "androforgeStoreFile",
    "ANDROFORGE_STORE_PASSWORD" to "androforgeStorePassword",
    "ANDROFORGE_KEY_ALIAS" to "androforgeKeyAlias",
    "ANDROFORGE_KEY_PASSWORD" to "androforgeKeyPassword"
).map { (environment, property) ->
    providers.environmentVariable(environment).orElse(providers.gradleProperty(property)).orNull
}
require(releaseSigning.all { it == null } || releaseSigning.all { !it.isNullOrEmpty() }) {
    "Incomplete release signing configuration: provide all four ANDROFORGE_* signing variables."
}

android {
    namespace = "fr.androforge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "fr.androforge"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.5.3"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static", "-DANDROFORGE_BUILD_TESTS=OFF") }
        }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        if (releaseSigning.all { it != null }) {
            create("publicRelease") {
                storeFile = file(releaseSigning[0]!!)
                storePassword = releaseSigning[1]
                keyAlias = releaseSigning[2]
                keyPassword = releaseSigning[3]
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("publicRelease")
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
        create("tabletPreview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-preview"
            matchingFallbacks += "release"
        }
        create("tabletValidation") {
            initWith(getByName("tabletPreview"))
            applicationIdSuffix = ".validation"
            versionNameSuffix = "-validation"
            matchingFallbacks += listOf("tabletPreview", "release")
        }
    }
    // Keep Git checkout and corresponding-source archive builds comparable.
    buildTypes.configureEach { vcsInfo { include = false } }
    packaging { jniLibs { useLegacyPackaging = false } }
    // Keep resources available for the app's fixed English locale in optional bundles.
    bundle { language { enableSplit = false } }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}
