plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Single place to bump the app version: major.minor.patch, versionCode = major*10000 + minor*100 + patch.
val appVersionName = "0.3.0"
val appVersionCode = appVersionName.split(".").let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }

android {
    namespace = "io.github.taehunkim.malbotdari"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.taehunkim.malbotdari"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Release signing: the key lives outside the repo (see docs/development.md). Nunsori and OndolSaem let apps signed
    // with their own key in without asking, so release builds of the three apps should share one key.
    fun signingProp(name: String): String? = (project.findProperty(name) as String?) ?: System.getenv(name)
    val releaseStore = signingProp("NUNSORI_STORE_FILE")
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = signingProp("NUNSORI_STORE_PASSWORD")
            keyAlias = signingProp("NUNSORI_KEY_ALIAS")
            keyPassword = signingProp("NUNSORI_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { generateLocaleConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

// Licenses shown in Settings › About: generated from the repo-root files (git-ignored output).
val licensesAsset = tasks.register("generateLicensesAsset") {
    val notices = rootProject.file("THIRD_PARTY_NOTICES.md")
    val license = rootProject.file("LICENSE")
    val out = file("src/main/assets/licenses.txt")
    inputs.files(notices, license)
    outputs.file(out)
    doLast { out.writeText(notices.readText() + "\n\n----------------------------------------\n\n" + license.readText()) }
}
tasks.named("preBuild") { dependsOn(licensesAsset) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    // The chat model runs in OndolSaem; the files and their index live in Nunsori, the notes in Jogakbo. All are reached through their SDKs.
    implementation(libs.ondolsaem.sdk)
    implementation(libs.nunsori.sdk)
    implementation(libs.jogakbo.sdk)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
