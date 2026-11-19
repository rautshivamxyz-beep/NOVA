#define MyAppName "NOVA"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "NOVA"

[Setup]
AppId={{B67C3D8A-9B3D-4F5D-A21D-9E0C4E6F5A12}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\NOVA
DefaultGroupName=NOVA
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\dist
OutputBaseFilename=NOVA-Setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
UninstallDisplayIcon={app}\NOVA.exe
ArchitecturesInstallIn64BitMode=x64
ArchitecturesAllowed=x64

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Dirs]
Name: "{app}\models"; Permissions: users-modify

[Files]
Source: "..\..\dist\NOVA-Desktop\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\NOVA"; Filename: "{app}\NOVA.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\NOVA"; Filename: "{app}\NOVA.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\NOVA.exe"; Description: "Launch NOVA"; Flags: postinstall nowait skipifsilent
