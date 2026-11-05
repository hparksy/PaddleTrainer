# Paddle Trainer

모스 부호 패들(전건) 연습 앱입니다. Windows PC와 Android에서 동작합니다.

- **키어:** Iambic A/B, 스트레이트 키 (사이드톤 포함, 저지연 오디오)
- **연습:** CW Academy, LICW, G4FON Koch, LCWO 중 고른 순서로 글자를 늘려 갑니다. 글자마다 리듬을 채점하고, 고칠 점과 교정 방법을 알려 줍니다.
  - 채점 기준: ITU-R M.1677 간격, Pierpont 「The Art & Skill of Radio-Telegraphy」, W8RIT 「Iambic Sending」
  - 최근에 틀렸거나 리듬이 늦었던 글자가 더 자주 나옵니다.
  - 한 번에 칠 단어 수를 1~3개에서 고릅니다.
  - 틀리면 같은 글자를 다시 칩니다 (화면에 `↻ 같은 글자 N회 더`로 표시, 최대 3회). 이 동작은 끌 수 있습니다.
- **검정:** 옛 아마추어무선기사 전신 실기 형식으로 영문 보통어를 송신하고, 성적표 형식으로 채점합니다.
  - 등급: 1분간 20자 / 35자(3급) / 50자(2급)
  - 감점 항목: 오자·탈자·용자, 자호불명료, 정정, 속도미달, 속도초과
- **통계:** 기록이 계속 쌓이고 글자별, 일별, 버릇별, 검정 문장별로 볼 수 있습니다.
- **설정:** 모든 채점 기준을 바꿀 수 있고 기본값으로 되돌릴 수 있습니다.

## 다운로드 (무료)

| 기기 | 받기 |
|---|---|
| 🖥️ Windows 10/11 (64비트) | **[⬇ Windows 설치 파일 다운로드](https://github.com/hparksy/PaddleTrainer/releases/latest/download/PaddleTrainer-Setup.exe)** |
| 📱 Android 8.0 이상 | **[⬇ Android 앱(APK) 다운로드](https://github.com/hparksy/PaddleTrainer/releases/latest/download/PaddleTrainer.apk)** |

- 위 링크는 항상 최신 버전을 받습니다.
- 🎯 **연습은 [연습 가이드](PRACTICE.md)대로 하세요.** 바른 자세, 하루 20분 연습 순서, 단계별 목표, 지적 사항 고치는 법, 좋은 습관과 피할 습관을 교재 기준으로 정리했습니다.
- 📖 **처음이면 [설치 방법](INSTALL.md)을 보세요.** 경고 창이 뜰 때 무엇을 누르면 되는지 순서대로 적어 두었습니다.
- 변경 사항은 [릴리즈 노트](CHANGELOG.md)에, 이전 버전은 [Releases](https://github.com/hparksy/PaddleTrainer/releases)에 있습니다.

## 패들 연결

- **USB 패들 어댑터:** `[` (dit)와 `]` (dah) 키를 보내는 키보드형 어댑터를 씁니다. 예: AliExpress 판매 어댑터의 모드 2.
  - PC: USB 포트에 그대로 꽂습니다.
  - Android: USB-C OTG 젠더로 연결합니다.
- **어댑터가 없을 때:**
  - **키보드만으로:** `[` = dit, `]` = dah, `Space` = 기준 소리 듣기. Android는 키보드를 연결하면 됩니다. 자세한 내용은 [설치 방법 → 키보드만으로 쓰기](INSTALL.md#키보드만으로-쓰기-패들이-없을-때)를 보세요.
  - PC: 화면에서 마우스 왼쪽 버튼(dit)과 오른쪽 버튼(dah)도 씁니다.
  - Android: 화면 아래 터치 패들을 씁니다. 두 손가락으로 스퀴즈도 됩니다.

> ⚠️ **블루투스 이어폰·스피커는 소리가 0.1~0.3초 늦게 들립니다.** 누른 순간과 소리가 어긋나 리듬 연습에 방해가 됩니다. 유선 이어폰이나 폰·PC 스피커를 쓰세요.

## 기록 위치

- **Windows:** `%APPDATA%\PaddleTrainer` (`attempts.csv`, `exams.csv`, `sentences.csv`, `settings.ini`). 앱을 지워도 남습니다.
- **Android:** 앱 내부 저장소입니다. 앱을 지우면 함께 지워집니다.

## 소스 구조

| 폴더 | 내용 |
|---|---|
| `core/` | 공통 채점 엔진: 키어, 해독, 리듬 채점, 검정 채점 (`morse.h`, 자체 검사 `test_morse.cpp`) |
| `desktop/` | Windows 앱 (Qt 5) + 설치 파일 스크립트 (Inno Setup) |
| `android/` | Android 앱 (Java). `Morse.java`는 `core/morse.h`를 그대로 옮긴 것입니다 |
| `tools/` | `build_all.bat`: 두 설치 파일을 `dist\`에 만듭니다. `release.ps1`: 태그, 푸시, GitHub 릴리즈를 만듭니다 |

- 버전 번호는 `VERSION` 파일 하나로 관리합니다. 두 앱과 설치 파일이 모두 이 번호를 씁니다.
- 변경 사항은 `CHANGELOG.md`에 적습니다.

## 라이선스

[MIT License](LICENSE)입니다. 저작권 표시만 남기면 누구나 자유롭게 쓰고, 고치고, 다시 배포할 수 있습니다.
- Windows 설치 파일에는 Qt(LGPL-3.0)와 VC++ 런타임이 함께 들어 있습니다. 자세한 내용은 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 보세요.
- 설치하면 `licenses` 폴더에 라이선스 전문이 들어갑니다.

## 직접 빌드

- **Windows:** Qt 5.15.2 (msvc2019_64), Visual Studio 2022, Inno Setup 6이 필요합니다.
- **Android:** JDK 17, Android SDK 35, Gradle 8.10이 필요합니다.
- 두 앱을 한 번에 빌드하려면 `tools\build_all.bat`을 실행합니다.
  - 따로 빌드할 때는 `desktop\make_installer.bat`, `android\build_apk.bat`을 씁니다.
- 각 빌드는 채점 자체 검사를 먼저 돌립니다. 검사에 실패하면 빌드를 멈춥니다.
