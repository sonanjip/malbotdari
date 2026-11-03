pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // OndolSaem and Nunsori client SDKs (see docs/development.md)
        maven("https://jitpack.io") { content { includeGroup("com.github.TaeHunKim") } }
    }
}
rootProject.name = "Malbotdari"
include(":app")

// Develop against sibling checkouts: `ondolsaem.dir=../ondolsaem` and `nunsori.dir=../nunsori` in local.properties
// build the SDKs from source instead of the JitPack artifacts (com.github.TaeHunKim:<repo>:<tag>).
val local = java.util.Properties().apply { file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
local.getProperty("ondolsaem.dir")?.let { dir ->
    includeBuild(dir) { dependencySubstitution { substitute(module("com.github.TaeHunKim:ondolsaem")).using(project(":sdk")) } }
}
local.getProperty("nunsori.dir")?.let { dir ->
    includeBuild(dir) { dependencySubstitution { substitute(module("com.github.TaeHunKim:Nunsori")).using(project(":sdk")) } }
}
