# Paddle Trainer

Two apps share one scoring core.

## Layout
- `core/`: `morse.h` (keyer, decoder, rhythm scoring, exam grading) and `test_morse.cpp` (its self-check). No UI.
- `desktop/`: Windows Qt5 app (`main.cpp`), `build.bat`, `make_installer.bat`, `installer.iss` (Inno Setup).
- `android/`: Android app (Java, no AndroidX).
  - `Morse.java` is a line-by-line port of `core/morse.h`.
  - `MorseTest.java` repeats `core/test_morse.cpp`.
- `tools/`:
  - `build_all.bat`: both installers into `dist/`.
  - `release.ps1`: tag, push, GitHub release.
- Root: `VERSION`, `CHANGELOG.md` (Korean release notes), `README.md`, `INSTALL.md` (Korean user install guide).

## Rules
- **Scoring changes go into both cores** (`core/morse.h` and `android/.../Morse.java`), plus a case in both self-checks.
  - Same inputs must give the same scores on both platforms.
  - Each build script runs its self-check first and stops on failure.
- **Feature parity:** a user-visible feature lands in both apps, or the CHANGELOG entry says which platform has it.
- **Records:** the CSV formats (`attempts.csv`, `exams.csv`, `sentences.csv`) are the same on both platforms. Changing them is a major version.

## Versioning and release notes (keep up on every user-visible change)
1. Bump `VERSION` (major.minor.patch). The PC title, installer, and Android versionName/versionCode all read it.
   - patch: a fix.
   - minor: a feature.
   - major: a change to the record format or the scoring model.
2. Add `## <VERSION> — <YYYY-MM-DD>` at the top of `CHANGELOG.md` in Korean, grouped by 연습 / 검정 / 기록 / 설정 / 설치.
3. Commit, then run `pwsh tools\release.ps1`. It refuses to run with uncommitted changes, a missing CHANGELOG section, or an existing tag.
   - It builds, tags `v<VERSION>`, and pushes to `origin` (GitHub, public) and `nas`.
   - It uploads `PaddleTrainer-Setup.exe` and `PaddleTrainer.apk` under fixed names, so the `releases/latest/download/...` links in README and INSTALL always serve the newest version.
