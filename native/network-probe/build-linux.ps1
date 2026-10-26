param([string]$BuilderImage='bo-tcp-info-builder:20261026')
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$source=(Resolve-Path -LiteralPath (Join-Path $root 'native/network-probe/linux-tcp-info.c')).Path
$out=[IO.Path]::GetFullPath((Join-Path $root '.tmp/network-probe-native-lib'))
if(-not $out.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {throw 'Outside workspace'}
$null=New-Item -ItemType Directory -Path $out -Force
& docker run --rm --network none --mount "type=bind,source=$root,target=/repo,readonly" --mount "type=bind,source=$out,target=/out" --entrypoint gcc $BuilderImage -O2 -fPIC -shared -Wall -Wextra -Werror -s '-Wl,--build-id=none' -I/usr/lib/jvm/java-17-openjdk-amd64/include -I/usr/lib/jvm/java-17-openjdk-amd64/include/linux /repo/native/network-probe/linux-tcp-info.c -o /out/bo-tcp-info-linux-x86_64.so
if($LASTEXITCODE -ne 0) {throw "Linux compiler failed: $LASTEXITCODE"}
Get-FileHash -LiteralPath (Join-Path $out 'bo-tcp-info-linux-x86_64.so') -Algorithm SHA256
