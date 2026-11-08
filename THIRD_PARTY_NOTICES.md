# Third-party notices

Malbotdari is licensed under the [Apache License 2.0](LICENSE), Copyright 2026 Taehun Kim.
It uses the components below. Versions come from [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Libraries (linked into the app)

| Component | Version | License |
|---|---|---|
| AndroidX (Compose BoM, Material 3, Activity, Navigation, Lifecycle, Room, WorkManager, DataStore, AppCompat) | see catalog (Compose BoM 2026.09.00, Room 2.8.5, WorkManager 2.12.0) | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | 2.4.21 / 1.11.0 / 1.12.0 | Apache-2.0 |
| OndolSaem client SDK | sdk-v0.3.0 | Apache-2.0 |
| Nunsori client SDK | sdk-v0.1.0 | Apache-2.0 |
| Jogakbo client SDK | sdk-v0.1.0 | Apache-2.0 |
| commonmark-java (+ GFM tables) | 0.32.0 | BSD-2-Clause |
| PDFBox-Android | 2.0.27.0 | Apache-2.0 |

## Models

No model is stored in this repository or bundled in the app. The chat model is downloaded and run by the companion app OndolSaem, whose THIRD_PARTY_NOTICES.md lists the models and runtimes with their licenses.

Office parsing, text decoding and audio decoding are adapted from the Apache-2.0 licensed Nunsori project.
