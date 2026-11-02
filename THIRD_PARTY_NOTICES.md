# Third-party notices

Paddle Trainer's own code is under the MIT License (see `LICENSE`). The Windows installer also ships the components below. The Android app uses only the Android platform and bundles no third-party code.

## Qt 5.15.2 (Windows app)

- **License:** GNU Lesser General Public License v3 (LGPL-3.0)
- **Copyright:** The Qt Company Ltd. and other contributors
- **Website:** https://www.qt.io
- **Source code:** https://download.qt.io/archive/qt/5.15/5.15.2/single/
- **Shipped files:** `Qt5Core.dll`, `Qt5Gui.dll`, `Qt5Widgets.dll` and the Qt plugins next to `PaddleTrainer.exe`. These are unmodified binaries.
- **Linking:** the app links Qt dynamically. You can replace these DLLs with your own build of Qt 5.15 (same compiler: MSVC 2019/2022, x64), and the app will use it.
- **License texts:** the installer places them in the `licenses` folder:
  - `Qt-LGPL-3.0.txt`
  - `Qt-GPL-3.0.txt` (the LGPL-3.0 refers to it)

## Microsoft Visual C++ Runtime (Windows app)

- **Shipped files:** `msvcp140*.dll` and `vcruntime140*.dll`, next to the app.
- **License:** redistributed under the Microsoft Visual Studio license terms for distributable code.
- **Copyright:** © Microsoft Corporation.
