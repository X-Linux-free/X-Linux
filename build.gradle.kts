plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.xlinux"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.xlinux"
        minSdk = 26
        targetSdk = 28   // allows running files from app storage on all Android versions
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging { jniLibs { useLegacyPackaging = true } }   // keep .so files extracted on disk
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.apache.commons:commons-compress:1.26.2") }

// Build-time cornerstone check: editing assets/cornerstone.txt stops the build.
val verifyCornerstone by tasks.registering {
    doLast {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest(file("src/main/assets/cornerstone.txt").readBytes()).joinToString("") { "%02x".format(it) }
        val exp = Regex("SHA = \"([0-9a-f]{64})\"").find(file("src/main/java/org/xlinux/Integrity.kt").readText())?.groupValues?.get(1)
        if (h != exp) throw GradleException("CORNERSTONE MODIFIED - build stopped")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyCornerstone) }
