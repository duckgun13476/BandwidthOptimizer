param(
    [ValidateSet('server','client')][string]$Action,
    [ValidateSet('enabled','old-client','old-server','disabled')][string]$Scenario='enabled',
    [switch]$NoFault
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$runName=if($NoFault){$Scenario+'-ordered'}else{$Scenario}
$base=[IO.Path]::GetFullPath((Join-Path $root ".tmp\compact-ref-native\$runName"))
if(!$base.StartsWith($root+[IO.Path]::DirectorySeparatorChar)) { throw 'Fixture path outside repository' }
$server=Join-Path $base 'server'
New-Item -ItemType Directory -Force -Path $server | Out-Null
$properties=Join-Path $server 'server.properties'
if(!(Test-Path -LiteralPath $properties)) {
    $text="server-ip=127.0.0.1`nserver-port=25850`nonline-mode=false`nview-distance=3`nsimulation-distance=3`nlevel-type=minecraft:flat`ngenerator-settings={}`nlevel-seed=27012026`nmax-tick-time=120000`nspawn-protection=0`n"
    [IO.File]::WriteAllText($properties,$text,[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $server 'eula.txt'),'eula=true',[Text.UTF8Encoding]::new($false))
}
$serverEnabled=$Scenario -in @('enabled','old-client')
$clientEnabled=$Scenario -in @('enabled','old-server')
Push-Location -LiteralPath $root
try {
    & (Join-Path $root 'gradlew.bat') ":neoforge-1.21.1:run$((Get-Culture).TextInfo.ToTitleCase($Action))" --configure-on-demand --offline --no-watch-fs --console=plain -I (Join-Path $root 'gradle\tests\compact-ref.init.gradle') "-PcompactScenario=$runName" "-PcompactForceMiss=$(!$NoFault)" "-PserverCompactRefs=$serverEnabled" "-PclientCompactRefs=$clientEnabled"
    if($LASTEXITCODE -ne 0) { throw "Native compact REF $Scenario/$Action failed: $LASTEXITCODE" }
} finally { Pop-Location }
