# Paddle Trainer

모스 부호 패들(전건) 연습 앱입니다. Windows PC와 Android에서 동작합니다.

- **키어:** Iambic A/B, 스트레이트 키 (사이드톤 포함, 저지연 오디오)
- **연습:** 코흐(Koch) 순서로 글자를 늘려 갑니다. 글자마다 리듬을 채점하고, 고칠 점과 교정 방법을 알려 줍니다.
  - 채점 기준: ITU-R M.1677 간격, Pierpont 「The Art & Skill of Radio-Telegraphy」, W8RIT 「Iambic Sending」
  - 자주 틀리는 글자가 더 자주 나옵니다.
  - 틀리면 같은 글자를 다시 칩니다 (화면에 `↻ 같은 글자 N회 더`로 표시, 최대 3회).
- **검정:** 옛 아마추어무선기사 전신 실기 형식으로 영문 보통어를 송신하고, 성적표 형식으로 채점합니다.
  - 등급: 1분간 20자 / 35자(3급) / 50자(2급)
  - 감점 항목: 오자·탈자·용자, 자호불명료, 정정, 속도미달, 속도초과
- **통계:** 기록이 계속 쌓이고 글자별, 일별, 버릇별, 검정 문장별로 볼 수 있습니다.
- **설정:** 모든 채점 기준을 바꿀 수 있고 기본값으로 되돌릴 수 있습니다.

## 다운로드

[Releases](../../releases/latest) 페이지에서 최신 버전을 받습니다. 버전별 변경 사항은 [CHANGELOG.md](CHANGELOG.md)에 있습니다.

| 기기 | 파일 | 설치 |
|---|---|---|
| Windows 10/11 (64비트) | `PaddleTrainer-Setup-<버전>.exe` | 실행 후 안내대로 설치합니다. 관리자 권한이 필요 없습니다. SmartScreen 경고가 뜨면 **추가 정보 → 실행**을 누릅니다 (코드 서명이 없어서 뜨는 경고입니다). |
| Android 8.0 이상 | `PaddleTrainer-<버전>.apk` | 폰에서 파일을 열고 **출처를 알 수 없는 앱 설치**를 허용합니다. |

## 패들 연결

- **USB 패들 어댑터:** `[` (dit)와 `]` (dah) 키를 보내는 키보드형 어댑터를 씁니다. 예: AliExpress 판매 어댑터의 모드 2.
  - PC: USB 포트에 그대로 꽂습니다.
  - Android: USB-C OTG 젠더로 연결합니다.
- **어댑터가 없을 때:**
  - PC: 화면에서 마우스 왼쪽 버튼(dit)과 오른쪽 버튼(dah)을 씁니다.
  - Android: 화면 아래 터치 패들을 씁니다. 두 손가락으로 스퀴즈도 됩니다.
- 블루투스 이어폰은 소리가 늦게 들려서 연습에 맞지 않습니다. 유선 이어폰이나 스피커를 쓰세요.

## 기록 위치

- **Windows:** `%APPDATA%\PaddleTrainer` (`attempts.csv`, `exams.csv`, `sentences.csv`, `settings.ini`). 앱을 지워도 남습니다.
- **Android:** 앱 내부 저장소입니다. 앱을 지우면 함께 지워집니다.

## 직접 빌드

- **Windows:** Qt 5.15.2 (msvc2019_64)와 Visual Studio 2022가 필요합니다.
  - `build.bat`: 앱만 빌드합니다.
  - `make_installer.bat`: 설치 파일까지 만듭니다 (Inno Setup 6 필요).
- **Android:** JDK 17, Android SDK 35, Gradle 8.10이 필요합니다.
  - `android\build_apk.bat`로 빌드합니다.
- 두 앱의 채점 코드는 같은 내용입니다: `morse.h`와 `android/.../Morse.java`.
  - 각 빌드 스크립트가 먼저 자체 검사(`test_morse.cpp`, `MorseTest.java`)를 돌립니다.
