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
        // OndolSaem, Nunsori and Jogakbo client SDKs (see docs/development.md)
        maven("https://jitpack.io") { content { includeGroup("com.github.sonanjip") } }
    }
}
rootProject.name = "Malbotdari"
include(":app")

// Develop against sibling checkouts: `ondolsaem.dir=../ondolsaem`, `nunsori.dir=../nunsori` and `jogakbo.dir=../jogakbo` in local.properties
// build the SDKs from source instead of the JitPack artifacts (com.github.sonanjip:<repo>:<tag>).
val local = java.util.Properties().apply { file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
local.getProperty("ondolsaem.dir")?.let { dir ->
    includeBuild(dir) { dependencySubstitution { substitute(module("com.github.sonanjip:ondolsaem")).using(project(":sdk")) } }
}
local.getProperty("nunsori.dir")?.let { dir ->
    includeBuild(dir) { dependencySubstitution { substitute(module("com.github.sonanjip:nunsori")).using(project(":sdk")) } }
}
local.getProperty("jogakbo.dir")?.let { dir ->
    includeBuild(dir) { dependencySubstitution { substitute(module("com.github.sonanjip:jogakbo")).using(project(":sdk")) } }
}
