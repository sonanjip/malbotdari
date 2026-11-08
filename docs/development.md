# Development

## Layout

One Gradle module, `app` (package and applicationId `io.github.taehunkim.malbotdari`). Scripts in `scripts/` (`env.sh`, `run.sh`, `screenshot.sh`, `logcat.sh`, `emu-start.sh`, `emu-stop.sh`) work like Nunsori's.

## SDKs (OndolSaem, Nunsori, Jogakbo)

Both client SDKs come from JitPack: `com.github.sonanjip:ondolsaem:<tag>` and `com.github.sonanjip:nunsori:<tag>` (`ondolsaemSdk`, `nunsoriSdk` in `gradle/libs.versions.toml`). The Jogakbo SDK is `com.github.sonanjip:jogakbo:<tag>` (`jogakboSdk`). To build against sibling checkouts, put `ondolsaem.dir=../ondolsaem`, `nunsori.dir=../nunsori` and `jogakbo.dir=../jogakbo` in `local.properties`: `settings.gradle.kts` then includes them as composite builds and substitutes the modules with their `:sdk` projects. Without these lines the published tags are used (`sdk-v0.5.0`, `sdk-v0.1.0`, `sdk-v0.1.0`).

## Testing

- Unit tests: `./gradlew testDebugUnitTest` (schedule math, context budget, previews, payloads, string parity).
- Instrumented: `ChatTest` (controller, tools, tasks, cards with a fake Nunsori index `FakeFileIndex` and a fake chat model), `NavigationTest` (first run, settings, up arrow), `NunsoriBridgeTest` and `JogakboBridgeTest` (against the Nunsori / Jogakbo installed on the device; skipped without them); `ChatTest` uses `FakeNotesSource` for the notes tools. `DecisionHostTest` asks OndolSaem's real decision model through `ModelHost.entail` (skipped without it); on the emulator a receipt scored 0.96 against "영수증", a trip plan 0.39 (unsure, so the assistant checks it by default). Run them with `adb shell am instrument -w io.github.taehunkim.malbotdari.test/androidx.test.runner.AndroidJUnitRunner` after `installDebug installDebugAndroidTest`, or `./gradlew connectedDebugAndroidTest` (which uninstalls the app afterwards).
- E2E with real models: install OndolSaem (with a chat model) and Nunsori (with an index); same debug key, so no approval prompts.

## Release build

Signing reads `NUNSORI_STORE_FILE`, `NUNSORI_STORE_PASSWORD`, `NUNSORI_KEY_ALIAS`, `NUNSORI_KEY_PASSWORD` (the same key as Nunsori and OndolSaem, so the apps trust each other). Without them `assembleRelease` builds an unsigned APK.

## Schema and version changes

`@Entity` changes: bump `AppDatabase.VERSION`, add a migration to `ALL_MIGRATIONS`, commit the schema JSON in `app/schemas`, add a migration test. No destructive fallback. App version: `appVersionName` in `app/build.gradle.kts`.

Tool-schema compaction tests run against a real JVM `org.json` implementation (test-only dependency), checking required arguments, enums and properties named `description`.

The `attachments/BoundedImport` helper is groundwork for local attachment imports. It copies to a new app-private destination with a caller-provided byte budget, removes a partial file on failure, and leaves input-stream ownership to the caller. It is not yet connected to a share receiver or an attachment processing pipeline; callers must enforce file-count and aggregate limits, manage cancellation and cleanup, and validate content separately. Its pure JVM tests can be run independently of Android, but they do not replace Android or migration tests.

Shared draft coverage is in `sharing/SharedDraftTest`: selected text persistence, the text limit, and rejection of file URIs. Image share and activity lifecycle behavior still require device testing.

`ImportBudgetTest` covers the file count and byte budgets. `ChatTest` covers local image tool scoping and the per-question image limit.

The Room 2 schema was reconstructed without running KSP; compare it with the generated schema and run the migration test before release.

Attachment tests include migration/cascade and semantic/keyword ranking. They require the normal Android and JVM test runs; no Gradle tasks were run while implementing these changes.

`ProcessTextAliasTest` launches the contextual-action alias, checks that it targets `MainActivity`, and verifies a quoted draft is created without sending a message.
