import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingStore = providers.gradleProperty("vvwikiSigningStore")
    .orElse(providers.environmentVariable("VVWIKI_SIGNING_STORE"))
    .orNull
val signingPassword = providers.gradleProperty("vvwikiSigningPassword")
    .orElse(providers.environmentVariable("VVWIKI_SIGNING_PASSWORD"))
    .orNull
val signingAlias = providers.gradleProperty("vvwikiSigningAlias")
    .orElse(providers.environmentVariable("VVWIKI_SIGNING_ALIAS"))
    .orElse("victor")
    .get()
val signingFile = signingStore?.let(::File)
val hasWikiSigning = signingFile?.isFile == true && !signingPassword.isNullOrBlank()
android {
    namespace = "com.victor.vvwiki"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.victor.vvwiki"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasWikiSigning) {
            create("wikiRelease") {
                storeFile = checkNotNull(signingFile)
                storePassword = checkNotNull(signingPassword)
                keyAlias = signingAlias
                keyPassword = checkNotNull(signingPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasWikiSigning) signingConfig = signingConfigs.getByName("wikiRelease")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        check(hasWikiSigning) {
            "Release APK must use the signing key recorded in the wiki. " +
                "Set VVWIKI_SIGNING_STORE, VVWIKI_SIGNING_PASSWORD, and VVWIKI_SIGNING_ALIAS."
        }
    }
}

tasks.register("verifyWikiSigningInputs") {
    doLast {
        check(hasWikiSigning) { "Wiki signing material is unavailable" }
        println("Wiki signing keystore and alias are available; secret values are not printed.")
    }
}

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.10.1.202505221210-r")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:6.10.1.202505221210-r") {
        exclude(group = "com.jcraft", module = "jsch")
    }
    implementation("com.github.mwiede:jsch:0.2.23")
    implementation("org.slf4j:slf4j-android:1.7.36")
}
