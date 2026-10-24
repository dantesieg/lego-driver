import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// versionCode = git commit count.
fun gitCommitCount(): Int =
    try {
        val p = ProcessBuilder("git", "rev-list", "--count", "HEAD").directory(rootDir).start()
        p.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
val buildNumber = gitCommitCount()

android {
    namespace = "si.bolukba.flip"
    compileSdk = 37

    defaultConfig {
        applicationId = "si.bolukba.flip"
        minSdk = 31
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // evgrab is an executable run via su, so it must be extracted to nativeLibraryDir.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

base {
    archivesName.set("flipdriver-v0.1.$buildNumber")
}
