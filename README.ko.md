# 말벗다리 (Malbotdari)

[English](README.md)

Android용 온디바이스 채팅 비서입니다. 계정도 인터넷도 필요 없고, 아무것도 폰 밖으로 나가지 않습니다. **말벗**은 이야기 상대, **다리**는 앱과 앱을 잇는 다리입니다. *My Assistant, Local, Bridging Other Tools · Dialog Across Records & Indexes.*

| 역할 | 앱 | 하는 일 |
|---|---|---|
| 채팅 | **말벗다리** (이 레포) | 대화, 예약 작업, 승인 카드 |
| AI 모델 | [온돌샘](https://github.com/TaeHunKim/ondolsaem) (필수) | 채팅 모델(Gemma 4, Qwen2.5)을 폰에서 받고 실행 |
| 내 파일 | [눈소리](https://github.com/TaeHunKim/Nunsori) (선택) | 사진·문서·음성·영상을 인덱싱하고, 비서는 눈소리를 통해 검색하고 읽음 |
| 내 노트 | [조각보](https://github.com/TaeHunKim/jogakbo) (선택) | 메모, 저장한 웹페이지·영상, 녹음과 그 요약. 비서는 조각보를 통해 검색하고 읽음 |

## 기능

| 기능 | 하는 일 |
|---|---|
| 채팅 | 고정 가능한 채팅별 기록, 복사·공유되는 마크다운 답변, 내 지침, 비전 모델용 사진 첨부 |
| 내 파일 (눈소리 연결 시) | 인덱싱된 파일 검색·읽기, 답변 아래 파일 미리보기, 눈소리에서 지은 인물 이름, 눈소리 검색의 "채팅으로 묻기" |
| 내 노트 (조각보 연결 시) | 노트와 요약 검색·읽기(`search_notes`, `read_note`), 찾은 노트를 누르면 조각보에서 열림 |
| 승인하는 카드 | 일정·연락처(시스템 앱이 채워진 채 열림), 파일 이동·휴지통(눈소리가 실행) |
| 예약 작업 | 나중에, 반복해서, 또는 눈소리가 맞는 파일을 인덱싱했을 때 실행하고 채팅과 알림으로 결과 전달 |
| 프라이버시 | `INTERNET` 권한 없음, 동반 앱과는 Android 기기 내 IPC로만 통신 |

영어/한국어 UI, 다크 모드.

## 요구 사항

Android 10 이상(minSdk 29), 채팅 모델을 받은 온돌샘, 파일 기능에는 눈소리 0.10.0 이상, 노트에는 조각보 0.1.0 이상.

## 빌드

```sh
source scripts/env.sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

SDK는 JitPack에서 받습니다. 형제 폴더 체크아웃으로 빌드하는 방법은 [docs/development.md](docs/development.md).

## 문서

[사용 안내](docs/user-guide.md) · [채팅](docs/chat.md) · [구조](docs/architecture.md) · [프라이버시](docs/privacy.md) · [개발](docs/development.md) · [로드맵](docs/roadmap.md)

## 라이선스

Apache License 2.0, [LICENSE](LICENSE)와 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 참고.
