# Development

## Layout

One Gradle module, `app` (package and applicationId `io.github.taehunkim.malbotdari`). Scripts in `scripts/` (`env.sh`, `run.sh`, `screenshot.sh`, `logcat.sh`, `emu-start.sh`, `emu-stop.sh`) work like Nunsori's.

## SDKs (OndolSaem, Nunsori)

Both client SDKs come from JitPack: `com.github.TaeHunKim:ondolsaem:<tag>` and `com.github.TaeHunKim:Nunsori:<tag>` (`ondolsaemSdk`, `nunsoriSdk` in `gradle/libs.versions.toml`). To build against sibling checkouts, put `ondolsaem.dir=../ondolsaem` and `nunsori.dir=../nunsori` in `local.properties`: `settings.gradle.kts` then includes them as composite builds and substitutes the modules with their `:sdk` projects. Until the first Nunsori SDK tag (`sdk-v0.1.0`) is published, `nunsori.dir` is required.

## Testing

- Unit tests: `./gradlew testDebugUnitTest` (schedule math, context budget, previews, payloads, string parity).
- Instrumented: `ChatTest` (controller, tools, tasks, cards with a fake Nunsori index `FakeFileIndex` and a fake chat model), `NavigationTest` (first run, settings, up arrow), `NunsoriBridgeTest` (against the Nunsori installed on the device; skipped without it). Run them with `adb shell am instrument -w io.github.taehunkim.malbotdari.test/androidx.test.runner.AndroidJUnitRunner` after `installDebug installDebugAndroidTest`, or `./gradlew connectedDebugAndroidTest` (which uninstalls the app afterwards).
- E2E with real models: install OndolSaem (with a chat model) and Nunsori (with an index); same debug key, so no approval prompts.

## Release build

Signing reads `NUNSORI_STORE_FILE`, `NUNSORI_STORE_PASSWORD`, `NUNSORI_KEY_ALIAS`, `NUNSORI_KEY_PASSWORD` (the same key as Nunsori and OndolSaem, so the apps trust each other). Without them `assembleRelease` builds an unsigned APK.

## Schema and version changes

`@Entity` changes: bump `AppDatabase.VERSION`, add a migration to `ALL_MIGRATIONS`, commit the schema JSON in `app/schemas`, add a migration test. No destructive fallback. App version: `appVersionName` in `app/build.gradle.kts`.
