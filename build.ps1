$DefaultTask = 'packageAllVersions'
$AllowRetryTasks = @('clean','build','assemble','jar','jarAllVersions','buildAllVersions','compileJava','compileTestJava','compileJavaAllVersions','compileAllVersions','packageAllVersions','allworkPackageAllVersions')
$WrapperPath = ''
$hasDefaultTaskOverride = $false
$rawArguments = @($args)
$GradleArguments = @()
for ($index = 0; $index -lt $rawArguments.Count; $index++) {
    $arg = $rawArguments[$index]
    if ($arg -eq '-DefaultTask') {
        if ($index + 1 -ge $rawArguments.Count) { throw '-DefaultTask requires a task name.' }
        $DefaultTask = $rawArguments[++$index]
        $hasDefaultTaskOverride = $true
    } elseif ($arg -eq '-WrapperPath') {
        if ($index + 1 -ge $rawArguments.Count) { throw '-WrapperPath requires a path.' }
        $WrapperPath = $rawArguments[++$index]
    } else {
        $GradleArguments += $arg
    }
}
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = $PSScriptRoot
if (-not $WrapperPath) {
    $wrapperCandidates = if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        @((Join-Path $root 'gradlew.bat'), (Join-Path $root 'gradlew'))
    } else {
        @((Join-Path $root 'gradlew'))
    }
    foreach ($candidate in $wrapperCandidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { $WrapperPath = $candidate; break }
    }
}
if (-not (Test-Path -LiteralPath $WrapperPath -PathType Leaf)) { throw 'Gradle wrapper not found.' }
$WrapperPath = (Resolve-Path -LiteralPath $WrapperPath).Path
$initPath = Join-Path $root 'gradle/nexus-cache.init.gradle'
if (-not (Test-Path -LiteralPath $initPath -PathType Leaf)) { throw 'Nexus companion init script missing.' }

$optionValueArgs = @('--max-workers','--project-dir','--project-cache-dir','--include-build','--exclude-task','--tests','--warning-mode','--console','--init-script','-I','-g','--gradle-user-home','-p','-b','--build-file','-c','--settings-file','-x','-P','-D','--args')
$cliTasks = @()
for ($index = 0; $index -lt $GradleArguments.Count; $index++) {
    $arg = $GradleArguments[$index]
    if ($optionValueArgs -contains $arg) { $index++; continue }
    if ($arg -match '^-' -or $arg -match '^\d+$') { continue }
    $cliTasks += $arg
}
$allArguments = @(
    if ($hasDefaultTaskOverride -or $cliTasks.Count -eq 0) { @($DefaultTask) + @($GradleArguments) }
    else { @($GradleArguments) }
)
$offline = $allArguments -contains '--offline' -or $allArguments -contains '--offline=true'
$unsafe = '(?i)(^|:).*(publish|uploadArchives|runClient|runServer|run|ReleaseBuild|verifyPublishArtifacts|verifyAllVersions|test|check).*$'
$tasks = @()
for ($index = 0; $index -lt $allArguments.Count; $index++) {
    $arg = $allArguments[$index]
    if ($optionValueArgs -contains $arg) { $index++; continue }
    if ($arg -match '^-' -or $arg -match '^\d+$') { continue }
    $tasks += $arg
}
$unsafeTask = @($tasks | Where-Object {
    $taskName = (($_ -split ':')[-1])
    $taskName -ne 'compileTestJava' -and $_ -match $unsafe
})
if ($unsafeTask.Count) { throw "This launcher never runs/retries publish, runtime, test, or release-gate tasks: $($unsafeTask -join ', ')" }
$allowed = @($AllowRetryTasks | ForEach-Object { $_.ToLowerInvariant() })
$retryable = ($tasks.Count -gt 0) -and (@($tasks | Where-Object { $allowed -notcontains (($_ -split ':')[-1]).ToLowerInvariant() }).Count -eq 0)

$configPath = $env:NEXUS_BUILD_CONFIG
if (-not $configPath) { $configPath = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle/nexus-build.properties' }
$configExists = Test-Path -LiteralPath $configPath -PathType Leaf
$nexusBase = $null
if ($configExists) {
    foreach ($line in Get-Content -LiteralPath $configPath) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^\s*([A-Za-z][A-Za-z0-9]*)\s*=\s*(.*?)\s*$') { throw 'Invalid Nexus build config syntax.' }
        $key = $Matches[1]; $value = $Matches[2]
        if ($key -match '(?i)password|secret|token|credential|username') { throw 'Nexus config must contain only baseUrl; do not put credentials here.' }
        if ($key -ne 'baseUrl' -or $nexusBase) { throw "Unsupported or duplicate Nexus setting: $key" }
        $nexusBase = $value
    }
    if (-not $nexusBase) { throw 'Nexus config exists but baseUrl is missing.' }
    try { $uri = [Uri]$nexusBase } catch { throw 'Nexus baseUrl is not a valid URI.' }
    if ($uri.Scheme -notin @('http','https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) { throw 'Nexus baseUrl must be http(s), without userinfo/query/fragment.' }
    $nexusBase = $nexusBase.TrimEnd('/')
}

$old = @{}
foreach ($name in @('MC_NEXUS_BASE_URL','MC_NEXUS_BYPASS','MC_NEXUS_INIT_SCRIPT','CLT_GRADLE_INIT_SCRIPT','NEXUS_BUILD_CONFIG','mcsnNasGradleMirror','MCSN_ALLOW_ONLINE_GRADLE')) { $old[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
$logBase = Join-Path ([IO.Path]::GetTempPath()) ('nexus-build-' + [guid]::NewGuid().ToString('N'))
$attemptLogs = @()
$locationPushed = $false
try {
    Push-Location -LiteralPath $root
    $locationPushed = $true
    $routes = if ($configExists) { @('Nexus','official online fallback eligible') } else { @('official online; no local config') }
    if ($offline) { $routes = @('explicit offline; no fallback') }
    Write-Host ("Build route: " + ($routes -join ', ') + "; task=$DefaultTask")
    $attemptLimit = if ($configExists -and -not $offline -and $retryable) { 2 } else { 1 }
    for ($attempt = 1; $attempt -le $attemptLimit; $attempt++) {
        $logPath = "$logBase-attempt$attempt.log"
        $attemptLogs += $logPath
        $useNexus = $configExists -and -not $offline -and $attempt -eq 1
        if ($useNexus) {
            $env:MC_NEXUS_BASE_URL = $nexusBase
            $env:MC_NEXUS_BYPASS = 'false'
            $env:MC_NEXUS_INIT_SCRIPT = $initPath
            $env:CLT_GRADLE_INIT_SCRIPT = $initPath
            $gradleArgs = @('--init-script',$initPath) + $allArguments
            if (-not $offline) { $env:MCSN_ALLOW_ONLINE_GRADLE = 'true' }
        } else {
            $env:MC_NEXUS_BASE_URL = ''
            $env:MC_NEXUS_BYPASS = 'true'
            $env:MC_NEXUS_INIT_SCRIPT = ''
            $env:CLT_GRADLE_INIT_SCRIPT = ''
            $env:NEXUS_BUILD_CONFIG = ''
            $env:mcsnNasGradleMirror = 'false'
            $gradleArgs = @($allArguments)
            if (-not $offline) {
                $env:MCSN_ALLOW_ONLINE_GRADLE = 'true'
                $gradleArgs += '-PmcsnNasGradleMirror=false'
            }
        }
        if ($attempt -eq 2) { Write-Warning 'Nexus dependency transport failed; one official-online retry is allowed.' }
        & $WrapperPath @gradleArgs 2>&1 | Tee-Object -FilePath $logPath | Out-Host
        $exitCode = $LASTEXITCODE
        if ($exitCode -eq 0) { exit 0 }
        if ($attempt -eq 1 -and $attemptLimit -eq 2) {
            $text = ($attemptLogs | ForEach-Object { Get-Content -LiteralPath $_ -Raw }) -join "`n"
            $isCompilationFailure = $text -match '(?im)(Compilation failed|\berror: |There were failing tests|tests? failed)'
            $resolverContext = $text -match '(?is)(Could not resolve|Could not get (?:resource|.* for)|Could not (?:GET|HEAD)|Failed to resolve|artifact.*(?:transfer|download)|Could not determine artifacts)'
            $transport = $text -match '(?i)(Remote host terminated the handshake|SSLHandshakeException|TLS handshake|ConnectTimeoutException|SocketTimeoutException|Read timed out|connect timed out|Connection reset|Connection refused|UnknownHostException|No such host is known|Could not resolve host|Received status code (500|502|503|504)|status code (500|502|503|504))'
            $ordinaryMissing = $text -match '(?i)(status code 404|HTTP 404|resource not found|Could not find .* in https?://)'
            if ($resolverContext -and $transport -and -not $ordinaryMissing -and -not $isCompilationFailure) { continue }
        }
        exit $exitCode
    }
} finally {
    if ($locationPushed) { Pop-Location -ErrorAction SilentlyContinue }
    foreach ($name in $old.Keys) { [Environment]::SetEnvironmentVariable($name,$old[$name],'Process') }
    if ($attemptLogs.Count) { Write-Host ("Build logs retained: " + ($attemptLogs -join ', ')) }
}
