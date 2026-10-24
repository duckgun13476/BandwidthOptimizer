param([string]$JavaHome='C:\Program Files\Java\jdk-21')
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$source=(Resolve-Path -LiteralPath (Join-Path $root 'native/network-probe/windows-tcp-info.c')).Path
$out=[IO.Path]::GetFullPath((Join-Path $root '.tmp/network-probe-native-lib'))
if(-not $out.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Outside workspace' }
$null=New-Item -ItemType Directory -Path $out -Force
$vswhere='C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
$vs=(& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath).Trim()
if(-not $vs) { throw 'MSVC x64 tools unavailable' }
$dev=(Resolve-Path -LiteralPath (Join-Path $vs 'Common7/Tools/VsDevCmd.bat')).Path
$include=(Resolve-Path -LiteralPath (Join-Path $JavaHome 'include')).Path
$dll=Join-Path $out 'bo-tcp-info-windows-x86_64.dll'
$object=Join-Path $out 'windows-tcp-info.obj'
$import=Join-Path $out 'windows-tcp-info.lib'
$compile='cl.exe /nologo /O2 /MT /LD /W4 /WX /I"'+$include+'" /I"'+$include+'\win32" /Fo"'+$object+'" "'+$source+'" /link /Brepro /OUT:"'+$dll+'" /IMPLIB:"'+$import+'" Ws2_32.lib'
$batch=Join-Path $out 'build.cmd'
$body="@echo off`r`ncall `"$dev`" -no_logo -arch=amd64`r`nif errorlevel 1 exit /b %errorlevel%`r`n$compile`r`nexit /b %errorlevel%`r`n"
[IO.File]::WriteAllText($batch,$body,[Text.UTF8Encoding]::new($false))
& $env:ComSpec /d /c $batch
if($LASTEXITCODE -ne 0) { throw "Native compiler failed: $LASTEXITCODE" }
Get-FileHash -LiteralPath $dll -Algorithm SHA256
Get-Item -LiteralPath $dll | Select-Object FullName,Length
