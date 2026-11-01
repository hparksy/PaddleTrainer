; Inno Setup script: per-user install (no admin), Start menu + optional desktop shortcut.
; Built by make_installer.bat from build\dist. Practice records in %APPDATA%\PaddleTrainer are kept on uninstall.
#define AppVer "1.0"

[Setup]
AppId={{5001FD5F-BF7E-406B-AD7A-49565A371994}
AppName=Paddle Trainer
AppVersion={#AppVer}
DefaultDirName={autopf}\PaddleTrainer
DefaultGroupName=Paddle Trainer
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=build\installer
OutputBaseFilename=PaddleTrainer-Setup-{#AppVer}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\PaddleTrainer.exe

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "build\dist\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Paddle Trainer"; Filename: "{app}\PaddleTrainer.exe"
Name: "{group}\{cm:UninstallProgram,Paddle Trainer}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Paddle Trainer"; Filename: "{app}\PaddleTrainer.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\PaddleTrainer.exe"; Description: "{cm:LaunchProgram,Paddle Trainer}"; Flags: nowait postinstall skipifsilent
