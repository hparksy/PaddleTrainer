# Paddle Trainer

Two apps share one scoring core: Windows Qt5 (`main.cpp`, `morse.h`) and Android Java (`android/`, where `Morse.java` mirrors `morse.h`). A change to scoring goes into both files and both self-checks (`test_morse.cpp`, `MorseTest.java`).

## Versioning and release notes (keep up on every user-visible change)
- `VERSION` holds the one version number (major.minor.patch). The PC title, the installer, and the Android versionName/versionCode all read it.
- With each change the user will see:
  1. Bump `VERSION`.
     - patch: a fix.
     - minor: a feature.
     - major: a change to the record format or the scoring model.
  2. Add a Korean section at the top of `CHANGELOG.md` with the date.
- To publish:
  1. Run `make_installer.bat` and `android\build_apk.bat`.
  2. Commit, then push to both `origin` (GitHub, public) and `nas`.
  3. Create GitHub release `v<VERSION>`.
     - Body: that version's CHANGELOG section.
     - Assets: `build\installer\PaddleTrainer-Setup-<VERSION>.exe` and `android\build\PaddleTrainer-<VERSION>.apk`.
