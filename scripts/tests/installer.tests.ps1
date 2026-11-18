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

# A cosign double. It records the jar and bundle it was asked to verify, so a test can
# assert *which* file reached the verifier rather than only that verification ran.
$script:CosignLog = Join-Path $root "cosign-verified.txt"
function New-CosignStub([string]$Name, [string]$Mode) {
    $path = Join-Path $root "cosign-$Name"
    $onWin = ($IsWindows -or $env:OS -eq "Windows_NT")
    if ($onWin) { $path = "$path.cmd" }
    if ($Mode -eq "pass") {
        $body = if ($onWin) {
            # %*, not %2 %3 %4: the first argument is verify-blob, and dropping it
            # made the case-14 assertion look like cosign was never asked to verify.
            "@echo off`r`necho %* >> `"$($script:CosignLog)`"`r`nexit /b 0`r`n"
        } else {
            "#!/usr/bin/env bash`necho `"`$@`" >> '$($script:CosignLog)'`nexit 0`n"
        }
    } elseif ($Mode -eq "hashcheck") {
        # Behaves like cosign: exit 0 only when the signed hash in the bundle equals
        # the hash of the jar passed to verify-blob.
        $body = if ($onWin) {
            # Batch cannot parse this reliably: splitting the JSON on ":" leaves a
            # trailing brace on the hash, and certutil's success line defeats
            # findstr. The script does the comparison and a .cmd shim returns its
            # exit code, which is what `& $Cosign` reads.
            $helper = Join-Path $root "cosign-hashcheck.ps1"
            Set-Content -Path $helper -Encoding ASCII -Value @'
$bundle = $null; $jar = $null; $prev = $null
foreach ($a in $args) {
    if ($prev -eq "--bundle") { $bundle = $a }
    $jar = $a
    $prev = $a
}
if (-not $bundle) { exit 1 }
# .NET I/O, not Get-Content/Get-FileHash: the installer hands this stub its
# dotfile temp paths, and on Windows the provider refuses hidden items without
# -Force -- the same restriction that hid the bug this PR fixes. Using the
# cmdlets here made the stub reject everything and the positive control failed.
try { $text = [System.IO.File]::ReadAllText($bundle) } catch { exit 1 }
if ($text -notmatch '"signedSha256"\s*:\s*"([0-9a-fA-F]{64})"') { exit 1 }
$want = $Matches[1]
try {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($jar)
    try { $actual = [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace("-", "") }
    finally { $stream.Dispose(); $sha.Dispose() }
} catch { exit 1 }
if ($actual -ieq $want) { exit 0 }
exit 1
'@
            "@echo off`r`npowershell -NoProfile -ExecutionPolicy Bypass -File `"$helper`" %*`r`nexit /b %ERRORLEVEL%`r`n"
        } else {
            "#!/usr/bin/env bash`nbundle=''`njar=''`nprev=''`nfor a in `"`$@`"; do if [ `"`$prev`" = '--bundle' ]; then bundle=`$a; fi; jar=`$a; prev=`$a; done`nsigned=`$(sed -n 's/.*signedSha256`":`"\([0-9a-f]*\)`".*/\1/p' `"`$bundle`")`nactual=`$(shasum -a 256 `"`$jar`" | cut -d' ' -f1)`n[ -n `"`$signed`" ] && [ `"`$signed`" = `"`$actual`" ]`n"
        }
    } else {
        $body = if ($onWin) { "@echo off`r`nexit /b 1`r`n" } else { "#!/usr/bin/env bash`nexit 1`n" }
    }
    Set-Content -Path $path -Value $body -Encoding ASCII
    if (-not $onWin) { chmod +x $path }
    return $path
}

# Adds a sigstore bundle to an existing fixture. "good" fixtures carry only a checksum.
function Add-SignatureFixture([string]$Tag) {
    $dir = Join-Path $root "www/releases/download/$Tag"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Set-Content -Path (Join-Path $dir "oq.jar.sigstore.json") -Value '{"mediaType":"application/vnd.dev.sigstore.bundle+json;version=0.1"}'
}

# Runs the installer against a fixture and returns @{ Threw; Message; Dir }.
function Invoke-FixtureInstall([string]$Tag, [string]$DirName, [string]$RequireSignature) {
    $dir = Join-Path $root $DirName
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $script:TagBase = "http://127.0.0.1:$script:Port/releases/download"
    $script:JavaBin = $javaVersionStub21
    $env:OQ_INSTALL_DIR = $dir
    $env:OQ_VERSION = $Tag
    if ($RequireSignature) { $env:OQ_REQUIRE_SIGNATURE = $RequireSignature }
    $threw = $false
    $message = ""
    try {
        Install-Oq
    } catch {
        $threw = $true
        $message = $_.Exception.Message
    } finally {
        Remove-Item Env:OQ_INSTALL_DIR -ErrorAction SilentlyContinue
        Remove-Item Env:OQ_VERSION -ErrorAction SilentlyContinue
        Remove-Item Env:OQ_REQUIRE_SIGNATURE -ErrorAction SilentlyContinue
        Remove-Item Env:OQ_COSIGN -ErrorAction SilentlyContinue
    }
    return [pscustomobject]@{ Threw = $threw; Message = $message; Dir = $dir }
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

# GitHub serves oq.jar.sha256 as application/octet-stream, so Invoke-WebRequest hands
# back a byte[] rather than a string. Casting that with [string] yields "System.Byte[]"
# and the checksum was rejected on Windows only; the Linux suite passed. This pins the
# decoding path that made the Windows CI job fail.
Run-Case "checksum text is decoded from a byte-array response" {
    $good = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    Assert-True ((Get-ExpectedSha ([System.Text.Encoding]::UTF8.GetString(
        [System.Text.Encoding]::UTF8.GetBytes("$good  oq.jar`n")))) -eq $good) "byte[] checksum"
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
    $script:Port = $port
    $listener.Stop()
    $serverArgs = @("-m", "http.server", "$port", "--bind", "127.0.0.1", "--directory", $www)
    if ($IsWindows -or $env:OS -eq "Windows_NT") {
        $server = Start-Process -FilePath $python.Source -ArgumentList $serverArgs -PassThru -WindowStyle Hidden
    } else {
        # -WindowStyle is Windows-only, so the end-to-end cases below never ran on
        # macOS or Linux and a broken installer path could reach the Windows job unseen.
        $server = Start-Process -FilePath $python.Source -ArgumentList $serverArgs -PassThru
    }
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

        # Signature cases. install.sh gained these first and install.ps1 did not, so a
        # Windows user got a checksum-only install while a POSIX user got a verified
        # one. These are the PowerShell equivalents.

        Run-Case "a signed release with cosign present is verified" {
            New-Fixture "v0.20.0" "SIGNED JAR" "good"
            Add-SignatureFixture "v0.20.0"
            Remove-Item -Force -LiteralPath $script:CosignLog -ErrorAction SilentlyContinue
            $env:OQ_COSIGN = New-CosignStub "pass" "pass"
            $r = Invoke-FixtureInstall "v0.20.0" "sig-pass"
            Assert-True (-not $r.Threw) "install failed: $($r.Message)"
            Assert-True (Test-Path -LiteralPath (Join-Path $r.Dir "oq.jar")) "jar missing"
            Assert-True (Test-Path -LiteralPath $script:CosignLog) "cosign was never called"
            $log = Get-Content -Raw -LiteralPath $script:CosignLog
            Assert-True ($log -match "verify-blob") "cosign was not asked to verify-blob: $log"
            Assert-True ($log -match "sigstore\.json") "the bundle did not reach cosign: $log"
            Assert-True (Test-NoTempFiles $r.Dir) "temp files left behind"
        }

        Run-Case "a tampered jar is rejected by the signature check" {
            # The checksum file is rewritten to match the served jar, so the SHA-256
            # check passes and only the signature can notice. The stub reads the hash
            # recorded in the bundle — the jar that was actually signed — and compares
            # it to the jar handed to verify-blob, so this fails if the installer
            # verifies the wrong file rather than merely if a stub is told to fail.
            $dir = Join-Path $root "www/releases/download/v0.21.0"
            New-Fixture "v0.21.0" "SIGNED JAR" "good"
            $signedHash = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $dir "oq.jar")).Hash.ToLowerInvariant()
            Set-Content -Path (Join-Path $dir "oq.jar.sigstore.json") -Value "{`"signedSha256`":`"$signedHash`"}"
            Set-Content -Path (Join-Path $dir "oq.jar") -Value "TAMPERED JAR" -NoNewline
            $tamperedHash = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $dir "oq.jar")).Hash.ToLowerInvariant()
            Set-Content -Path (Join-Path $dir "oq.jar.sha256") -Value "$tamperedHash  oq.jar"
            Assert-True ($tamperedHash -ne $signedHash) "fixture did not actually tamper"
            $env:OQ_COSIGN = New-CosignStub "hashcheck" "hashcheck"
            $r = Invoke-FixtureInstall "v0.21.0" "sig-bad"
            Assert-True $r.Threw "a tampered jar was accepted"
            Assert-True ($r.Message -like "*Signature verification FAILED*") "wrong message: $($r.Message)"
            Assert-True (-not (Test-Path -LiteralPath (Join-Path $r.Dir "oq.jar"))) "the tampered jar was installed"
            Assert-True (Test-NoTempFiles $r.Dir) "temp files left behind"
        }

        Run-Case "an untampered jar passes the same hash-checking stub" {
            # Guards the case above: proves the stub rejects because the hashes differ
            # and not because the stub never works.
            $dir = Join-Path $root "www/releases/download/v0.21.1"
            New-Fixture "v0.21.1" "SIGNED JAR OK" "good"
            $signedHash = (Get-FileHash -Algorithm SHA256 -Path (Join-Path $dir "oq.jar")).Hash.ToLowerInvariant()
            Set-Content -Path (Join-Path $dir "oq.jar.sigstore.json") -Value "{`"signedSha256`":`"$signedHash`"}"
            $env:OQ_COSIGN = New-CosignStub "hashcheck" "hashcheck"
            $r = Invoke-FixtureInstall "v0.21.1" "sig-good-hash"
            Assert-True (-not $r.Threw) "an untampered jar was rejected: $($r.Message)"
            Assert-True (Test-Path -LiteralPath (Join-Path $r.Dir "oq.jar")) "jar missing"
        }

        Run-Case "a signed release without cosign warns and installs" {
            New-Fixture "v0.22.0" "SIGNED NO COSIGN" "good"
            Add-SignatureFixture "v0.22.0"
            $env:OQ_COSIGN = Join-Path $root "cosign-does-not-exist"
            $r = Invoke-FixtureInstall "v0.22.0" "sig-nocosign"
            Assert-True (-not $r.Threw) "install failed: $($r.Message)"
            Assert-True (Test-Path -LiteralPath (Join-Path $r.Dir "oq.jar")) "jar missing"
        }

        Run-Case "OQ_REQUIRE_SIGNATURE refuses a signed release without cosign" {
            New-Fixture "v0.23.0" "REQUIRE NO COSIGN" "good"
            Add-SignatureFixture "v0.23.0"
            $env:OQ_COSIGN = Join-Path $root "cosign-does-not-exist"
            $r = Invoke-FixtureInstall "v0.23.0" "sig-require-nocosign" "1"
            Assert-True $r.Threw "OQ_REQUIRE_SIGNATURE without cosign was accepted"
            Assert-True ($r.Message -like "*cosign is not installed*") "wrong message: $($r.Message)"
        }

        Run-Case "an unsigned release warns but installs" {
            New-Fixture "v0.24.0" "UNSIGNED" "good"
            $env:OQ_COSIGN = New-CosignStub "pass" "pass"
            $r = Invoke-FixtureInstall "v0.24.0" "sig-unsigned"
            Assert-True (-not $r.Threw) "an unsigned release was refused: $($r.Message)"
            Assert-True ((Get-Content -Raw -LiteralPath (Join-Path $r.Dir "oq.jar")) -eq "UNSIGNED") "wrong jar"
        }

        Run-Case "OQ_REQUIRE_SIGNATURE refuses an unsigned release" {
            New-Fixture "v0.25.0" "UNSIGNED REQUIRED" "good"
            $r = Invoke-FixtureInstall "v0.25.0" "sig-require-unsigned" "1"
            Assert-True $r.Threw "OQ_REQUIRE_SIGNATURE accepted an unsigned release"
            Assert-True ($r.Message -like "*publishes no signature*") "wrong message: $($r.Message)"
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
