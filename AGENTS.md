# Malbotdari / 말벗다리 (Android)
온디바이스 채팅 비서. applicationId·패키지 `io.github.taehunkim.malbotdari`, Kotlin + Compose, minSdk 29 / target 36 / compile 37. 모델은 온돌샘(`../ondolsaem`), 파일·인덱스는 눈소리(`../nunsori`), 노트는 조각보(`../jogakbo`)에 있고 모두 SDK(AIDL)로만 접근한다. INTERNET·파일 권한 없음.

## 문서 유지 규칙
코드를 바꾸면 같은 커밋에서 관련 문서도 고친다(필요 없으면 이유 한 줄). 새 기능·화면·설정 → README(en/ko 기능 표), `docs/user-guide.md`; 채팅·도구·예약 작업 → `docs/chat.md`; 구조·데이터 → `docs/architecture.md`; 권한·저장 데이터 → `docs/privacy.md`; 빌드·테스트 → `docs/development.md`; 계획·한계 → `docs/roadmap.md`; 의존성 → `THIRD_PARTY_NOTICES.md`. 문자열은 `values/strings.xml`(en)과 `values-ko/strings.xml`(ko) 키가 같아야 한다(`StringsParityTest`).

## 스키마·버전
`@Entity` 변경 시 `AppDatabase.VERSION` 증가 + `Migrations.kt`(`ALL_MIGRATIONS`) + `app/schemas` JSON + 마이그레이션 테스트. destructive 폴백 금지. 앱 버전은 `app/build.gradle.kts`의 `appVersionName`.

## 환경·워크플로우
- `source scripts/env.sh`(JBR, SDK, adb). `scripts/run.sh`, `scripts/screenshot.sh`, `scripts/logcat.sh`, `scripts/emu-start.sh`.
- `local.properties`: `ondolsaem.dir=../ondolsaem`, `nunsori.dir=../nunsori`, `jogakbo.dir=../jogakbo`(composite build; 눈소리·조각보 SDK가 JitPack에 나오기 전까지 필수).
- `./gradlew assembleDebug testDebugUnitTest lintDebug`. 계측 테스트는 `installDebug installDebugAndroidTest` 후 `adb shell am instrument -w io.github.taehunkim.malbotdari.test/androidx.test.runner.AndroidJUnitRunner`(`connectedDebugAndroidTest`는 끝나고 앱을 지운다).

## 구조 (요약)
- `chat/`: `ChatController`(턴·컨텍스트·도구), `NunsoriChatTool`(눈소리 도구를 그대로 모델에 노출, move/delete는 확인 → 카드 → `commit`), `chat/schedule/TaskManager`(시간 작업은 알람, 파일 작업은 눈소리 `INDEX_CHANGED` 힌트 → `IndexChangedWorker` → `changesSince` 커서).
- `files/`: `FileIndex`(테스트는 `FakeFileIndex`), `NunsoriBridge`(연결 상태). `notes/`: `NotesSource`(테스트는 `FakeNotesSource`), `JogakboBridge` → `JogakboChatTool`(`search_notes`, `read_note`). `ml/ModelHost`: 온돌샘 채팅 모델.
- 자세히: `docs/architecture.md`, `docs/chat.md`.

## 테스트 메모
- `ChatTest`는 가짜 모델·가짜 인덱스로 돈다(온돌샘·눈소리 불필요). `NunsoriBridgeTest`는 기기의 눈소리가 있을 때만(같은 debug 키라 승인 없이 연결).
