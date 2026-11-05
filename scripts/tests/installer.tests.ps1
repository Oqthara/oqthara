# Tests for scripts/install.ps1.
# Run: pwsh -NoProfile -File scripts/tests/installer.tests.ps1
# Prints ok/not ok lines. No Pester, no network.
$ErrorActionPreference = "Stop"

$script:Passed = 0
$script:Failed = 0
$script:Index = 0

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$installer = Join-Path (Split-Path -Parent $here) "install.ps1"
$root = Join-Path ([System.IO.Path]::GetTempPath()) ("oq-ps-tests-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $root | Out-Null

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Run-Case([string]$Name, [scriptblock]$Body) {
    $script:Index++
    try {
        & $Body
        Write-Host ("ok {0} - {1}" -f $script:Index, $Name)
        $script:Passed++
    } catch {
        Write-Host ("not ok {0} - {1}: {2}" -f $script:Index, $Name, $_.Exception.Message)
        $script:Failed++
    }
}

# Load install.ps1 without running Install-Oq.
$env:OQ_INSTALLER_LIB = "1"
. $installer
Remove-Item Env:OQ_INSTALLER_LIB -ErrorAction SilentlyContinue

# Test double: turn fatal installer errors into catchable exceptions and never
# touch the real user PATH.
function Fail([string]$Message) { throw $Message }
function Add-UserPath([string]$Entry) { return $false }

$javaVersionStub17 = Join-Path $root "java17"
$javaVersionStub21 = Join-Path $root "java21"
$stubJavaBody17 = "#!/usr/bin/env bash`nif [ `"`${1:-}`" = `"-version`" ]; then echo 'openjdk version `"17.0.1`" 2021-10-19' >&2; exit 0; fi`nexit 1`n"
$stubJavaBody21 = "#!/usr/bin/env bash`nif [ `"`${1:-}`" = `"-version`" ]; then echo 'openjdk version `"21.0.9`" 2025-01-01' >&2; exit 0; fi`njar=''`nprev=''`nfor a in `"`$@`"; do if [ `"`$prev`" = '-jar' ]; then jar=`$a; fi; prev=`$a; done`nif [ ! -s `"`$jar`" ]; then echo 'invalid jar' >&2; exit 1; fi`necho 'oq 0.12.0'`n"

if ($IsWindows -or $env:OS -eq "Windows_NT") {
    $javaVersionStub17 = "$javaVersionStub17.cmd"
    $javaVersionStub21 = "$javaVersionStub21.cmd"
    Set-Content -Path $javaVersionStub17 -Value "@echo off`r`nif `"%1`"==`"-version`" (echo openjdk version `"17.0.1`" 1>&2 & exit /b 0)`r`nexit /b 1`r`n"
    Set-Content -Path $javaVersionStub21 -Value "@echo off`r`nif `"%1`"==`"-version`" (echo openjdk version `"21.0.9`" 1>&2 & exit /b 0)`r`necho oq 0.12.0`r`nexit /b 0`r`n"
} else {
    Set-Content -Path $javaVersionStub17 -Value $stubJavaBody17
    Set-Content -Path $javaVersionStub21 -Value $stubJavaBody21
    chmod +x $javaVersionStub17
    chmod +x $javaVersionStub21
}

function New-Fixture([string]$Tag, [string]$Content, [string]$Mode) {
    $dir = Join-Path $root "www/releases/download/$Tag"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Set-Content -Path (Join-Path $dir "oq.jar") -Value $Content -NoNewline
    $hash = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $dir "oq.jar")).Hash.ToLowerInvariant()
    switch ($Mode) {
        "good" { Set-Content -Path (Join-Path $dir "oq.jar.sha256") -Value "$hash  oq.jar" }
        "star" { Set-Content -Path (Join-Path $dir "oq.jar.sha256") -Value "$hash *oq.jar" }
        "bad"  { Set-Content -Path (Join-Path $dir "oq.jar.sha256") -Value ("0" * 64 + "  oq.jar") }
        "none" { }
    }
}

function Test-NoTempFiles([string]$Dir) {
    if (-not (Test-Path -LiteralPath $Dir)) { return $true }
    $left = @(Get-ChildItem -LiteralPath $Dir -Force -Filter ".*.tmp.*" -ErrorAction SilentlyContinue)
    return ($left.Count -eq 0)
}

# ---------------------------------------------------------------------------
Run-Case "version normalization adds a leading v" {
    Assert-True ((Get-NormalizedTag "0.1.0-alpha.2") -eq "v0.1.0-alpha.2") "missing v"
    Assert-True ((Get-NormalizedTag "v0.1.0-alpha.2") -eq "v0.1.0-alpha.2") "duplicate v"
}

Run-Case "Java major version parses both formats" {
    Assert-True ((Get-JavaMajorVersion "21.0.1") -eq 21) "21.0.1"
    Assert-True ((Get-JavaMajorVersion "1.8.0_402") -eq 8) "1.8"
    Assert-True ((Get-JavaMajorVersion "17.0.1") -eq 17) "17.0.1"
}

Run-Case "unsupported Java 17 is rejected" {
    $script:JavaBin = $javaVersionStub17
    $threw = $false
    try { $null = Assert-Java21 } catch { $threw = $true; Assert-True ($_.Exception.Message -like "*Java 21+*") "message: $($_.Exception.Message)" }
    Assert-True $threw "Java 17 was accepted"
}

Run-Case "Java 21 is accepted" {
    $script:JavaBin = $javaVersionStub21
    $found = Assert-Java21
    Assert-True ($found -like "21.*") "found: $found"
}

Run-Case "sha256 file formats are accepted" {
    $good = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    Assert-True ((Get-ExpectedSha "$good  oq.jar") -eq $good) "two-space format"
    Assert-True ((Get-ExpectedSha "$good *oq.jar") -eq $good) "star format"
    Assert-True ((Get-ExpectedSha "$($good.ToUpperInvariant())  oq.jar") -eq $good) "uppercase hash"
    Assert-True ($null -eq (Get-ExpectedSha "not-a-hash  oq.jar")) "invalid format"
}

Run-Case "PATH dedupe is case-insensitive and path aware" {
    $path = "C:\Windows;C:\Tools\Oq\;"
    Assert-True (Test-PathContainsEntry $path "c:\tools\oq") "case-insensitive match failed"
    Assert-True (Test-PathContainsEntry $path "C:\Tools\Oq") "trailing separator mismatch"
    Assert-True (-not (Test-PathContainsEntry $path "C:\Tools\Other")) "false positive"
    Assert-True (-not (Test-PathContainsEntry $path "C:\Tools")) "prefix false positive"
    Assert-True (-not (Test-PathContainsEntry $path "")) "empty entry matched"
}

Run-Case "checksum mismatch preserves the previous jar" {
    $dir = Join-Path $root "mismatch"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $jar = Join-Path $dir "oq.jar"
    Set-Content -Path $jar -Value "PREVIOUS" -NoNewline
    $before = [System.IO.File]::ReadAllBytes($jar)
    New-Fixture "v0.6.0" "NEW" "bad"
    $fixtureDir = Join-Path $root "www/releases/download/v0.6.0"
    $tmp = Join-Path $dir ".oq.jar.tmp.$PID"
    Copy-Item -LiteralPath (Join-Path $fixtureDir "oq.jar") -Destination $tmp
    $expected = Get-ExpectedSha (Get-Content -Raw -LiteralPath (Join-Path $fixtureDir "oq.jar.sha256"))
    $actual = Get-Sha256 $tmp
    Assert-True ($actual -ne $expected) "fixture did not mismatch"
    Remove-Item -Force -LiteralPath $tmp
    $after = [System.IO.File]::ReadAllBytes($jar)
    Assert-True ([Convert]::ToBase64String($before) -eq [Convert]::ToBase64String($after)) "previous jar changed"
    Assert-True (Test-NoTempFiles $dir) "temp files left behind"
}

Run-Case "missing checksum asset is detected" {
    New-Fixture "v0.4.0" "NO SHA" "none"
    $shaPath = Join-Path $root "www/releases/download/v0.4.0/oq.jar.sha256"
    Assert-True (-not (Test-Path -LiteralPath $shaPath)) "fixture should have no sha"
    $text = if (Test-Path -LiteralPath $shaPath) { Get-Content -Raw -LiteralPath $shaPath } else { "" }
    Assert-True ($null -eq (Get-ExpectedSha $text)) "empty checksum should be rejected"
}

Run-Case "temp files are cleaned up after a failed install" {
    $dir = Join-Path $root "cleanup"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $tmp = Join-Path $dir ".oq.jar.tmp.$PID"
    Set-Content -Path $tmp -Value "x" -NoNewline
    try {
        throw "simulated failure"
    } catch {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -Force -LiteralPath $tmp -ErrorAction SilentlyContinue }
    }
    Assert-True (Test-NoTempFiles $dir) "temp file survived"
}

# End-to-end install against a local fixture server when python is available.
$server = $null
$python = Get-Command python3 -ErrorAction SilentlyContinue
if (-not $python) { $python = Get-Command python -ErrorAction SilentlyContinue }
$www = Join-Path $root "www"
New-Item -ItemType Directory -Force -Path $www | Out-Null

if ($python) {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    $server = Start-Process -FilePath $python.Source -ArgumentList @("-m", "http.server", "$port", "--bind", "127.0.0.1", "--directory", $www) -PassThru -WindowStyle Hidden
    $ready = $false
    for ($i = 0; $i -lt 100; $i++) {
        try {
            Invoke-WebRequest -Uri "http://127.0.0.1:$port/" -UseBasicParsing -TimeoutSec 1 | Out-Null
            $ready = $true
            break
        } catch {
            Start-Sleep -Milliseconds 50
        }
    }
    if ($ready) {
        $discoveryApi = Join-Path $www "api.json"
        Set-Content -Path $discoveryApi -Value '[{"tag_name":"v9.9.9-draft","draft":true},{"tag_name":"v0.1.0-alpha.2","draft":false,"prerelease":true}]'

        Run-Case "API discovery skips drafts and selects the prerelease" {
            $script:ReleaseApi = "http://127.0.0.1:$port/api.json"
            $tag = Get-LatestTag
            Assert-True ($tag -eq "v0.1.0-alpha.2") "tag: $tag"
        }

        Run-Case "end-to-end pinned install verifies and writes launcher" {
            New-Fixture "v0.12.0" "PS JAR" "good"
            $dir = Join-Path $root "e2e"
            $script:TagBase = "http://127.0.0.1:$port/releases/download"
            $script:JavaBin = $javaVersionStub21
            $env:OQ_INSTALL_DIR = $dir
            $env:OQ_VERSION = "0.12.0"
            try {
                Install-Oq
            } finally {
                Remove-Item Env:OQ_INSTALL_DIR -ErrorAction SilentlyContinue
                Remove-Item Env:OQ_VERSION -ErrorAction SilentlyContinue
            }
            Assert-True (Test-Path -LiteralPath (Join-Path $dir "oq.jar")) "jar missing"
            Assert-True (Test-Path -LiteralPath (Join-Path $dir "oq.cmd")) "launcher missing"
            $content = (Get-Content -Raw -LiteralPath (Join-Path $dir "oq.jar"))
            Assert-True ($content -eq "PS JAR") "wrong jar content: $content"
            Assert-True (Test-NoTempFiles $dir) "temp files left behind"
        }

        Run-Case "end-to-end checksum mismatch keeps the old jar" {
            New-Fixture "v0.13.0" "BAD NEW" "bad"
            $dir = Join-Path $root "e2e-bad"
            New-Item -ItemType Directory -Force -Path $dir | Out-Null
            Set-Content -Path (Join-Path $dir "oq.jar") -Value "OLD JAR" -NoNewline
            $script:TagBase = "http://127.0.0.1:$port/releases/download"
            $script:JavaBin = $javaVersionStub21
            $env:OQ_INSTALL_DIR = $dir
            $env:OQ_VERSION = "v0.13.0"
            $threw = $false
            try {
                Install-Oq
            } catch {
                $threw = $true
            } finally {
                Remove-Item Env:OQ_INSTALL_DIR -ErrorAction SilentlyContinue
                Remove-Item Env:OQ_VERSION -ErrorAction SilentlyContinue
            }
            Assert-True $threw "mismatch was accepted"
            Assert-True ((Get-Content -Raw -LiteralPath (Join-Path $dir "oq.jar")) -eq "OLD JAR") "old jar replaced"
            Assert-True (Test-NoTempFiles $dir) "temp files left behind"
        }
    } else {
        Write-Host "not ok $($script:Index + 1) - fixture server did not start"
        $script:Index++
        $script:Failed++
    }
} else {
    Write-Host "ok $($script:Index + 1) - end-to-end install skipped (python not available)"
    $script:Index++
    $script:Passed++
}

try {
    if ($server) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item -Recurse -Force -LiteralPath $root -ErrorAction SilentlyContinue
} catch {
    Write-Host "warning: cleanup failed: $($_.Exception.Message)"
}

Write-Host ("{0} passing" -f $script:Passed)
if ($script:Failed -gt 0) {
    Write-Host ("{0} failing" -f $script:Failed)
    exit 1
}
exit 0
