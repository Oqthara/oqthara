# Installs the oq CLI on Windows. Requires Java 21+.
#
# OQ_VERSION accepts "latest" (default), "0.1.0-alpha.2" or "v0.1.0-alpha.2".
# OQ_INSTALL_DIR defaults to "$env:LOCALAPPDATA\oq".
# OQ_REQUIRE_SIGNATURE=1 refuses a release whose signature is missing or unverifiable.
#
# Test seams (defaults are the real endpoints):
#   OQ_RELEASE_API       default https://api.github.com/repos/Oqthara/oqthara/releases?per_page=30
#   OQ_RELEASE_TAG_BASE  default https://github.com/Oqthara/oqthara/releases/download
#   OQ_JAVA              default java
#   OQ_COSIGN            default the cosign on PATH, if any
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$script:AtomUrl = "https://github.com/Oqthara/oqthara/releases.atom"
$script:ReleaseApi = if ($env:OQ_RELEASE_API) { $env:OQ_RELEASE_API } else { "https://api.github.com/repos/Oqthara/oqthara/releases?per_page=30" }
$script:TagBase = if ($env:OQ_RELEASE_TAG_BASE) { $env:OQ_RELEASE_TAG_BASE } else { "https://github.com/Oqthara/oqthara/releases/download" }
$script:JavaBin = if ($env:OQ_JAVA) { $env:OQ_JAVA } else { "java" }

function Write-Info([string]$Message) {
    Write-Host $Message
}

function Fail([string]$Message) {
    throw [System.Exception]::new($Message)
}

function Get-RemoteText([string]$Uri) {
    $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 60
    $content = $response.Content
    if ($content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($content)
    }
    if ($content -is [string]) {
        return $content
    }
    $stream = $response.RawContentStream
    if ($null -eq $stream) { return [string]$content }
    $stream.Position = 0
    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
    try {
        return $reader.ReadToEnd()
    } finally {
        $reader.Dispose()
    }
}

function Save-RemoteFile([string]$Uri, [string]$OutFile) {
    Invoke-WebRequest -Uri $Uri -UseBasicParsing -OutFile $OutFile -TimeoutSec 300
}

function Get-LatestTag {
    try {
        $body = Get-RemoteText $script:ReleaseApi
        $releases = @($body | ConvertFrom-Json)
        foreach ($release in $releases) {
            if ($release.draft) { continue }
            $tag = [string]$release.tag_name
            if ($tag) { return $tag }
        }
    } catch {
        # fall through to the atom feed
    }
    try {
        $atom = Get-RemoteText $script:AtomUrl
        $match = [regex]::Match([string]$atom, 'releases/tag/([^"<]+)')
        if ($match.Success) { return $match.Groups[1].Value }
    } catch {
        # no feed either
    }
    return $null
}

function Get-NormalizedTag([string]$Version) {
    if ($Version.StartsWith("v")) { return $Version }
    return "v$Version"
}

function Get-JavaMajorVersion([string]$VersionText) {
    if ($VersionText.StartsWith("1.")) {
        $parts = $VersionText.Split(".")
        return [int]$parts[1]
    }
    $parts = $VersionText.Split(".")
    return [int]$parts[0]
}

function Test-JavaCommand([string]$Candidate) {
    if (Test-Path -LiteralPath $Candidate) { return $true }
    return [bool](Get-Command $Candidate -ErrorAction SilentlyContinue)
}

function Assert-Java21 {
    if (-not (Test-JavaCommand $script:JavaBin)) {
        Fail "Java 21+ is required (java was not found on PATH)."
    }
    $raw = (& $script:JavaBin -version 2>&1 | Out-String)
    $match = [regex]::Match($raw, '"([^"]+)"')
    if (-not $match.Success) {
        Fail "Could not determine the Java version from: $raw"
    }
    $found = $match.Groups[1].Value
    $major = Get-JavaMajorVersion $found
    if ($major -lt 21) {
        Fail "Java 21+ is required (found $found)."
    }
    return $found
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
}

function Get-ExpectedSha([string]$ShaText) {
    $match = [regex]::Match(([string]$ShaText).ToLowerInvariant(), '(?m)^\s*([0-9a-f]{64})\b')
    if (-not $match.Success) { return $null }
    return $match.Groups[1].Value.ToLowerInvariant()
}

# Returns the path to cosign, or $null when it is not on PATH. OQ_COSIGN overrides
# the lookup so the installer tests can point at a stub.
function Get-CosignCommand {
    if ($env:OQ_COSIGN) {
        if (Test-Path -LiteralPath $env:OQ_COSIGN) { return $env:OQ_COSIGN }
        return $null
    }
    $found = Get-Command cosign -CommandType Application -ErrorAction SilentlyContinue
    if (-not $found) { return $null }
    return $found.Source
}

function Test-Signature {
    param(
        [Parameter(Mandatory = $true)][string]$Cosign,
        [Parameter(Mandatory = $true)][string]$Bundle,
        [Parameter(Mandatory = $true)][string]$Jar
    )
    & $Cosign verify-blob --bundle $Bundle $Jar *> $null
    return ($LASTEXITCODE -eq 0)
}

# Downloads a small text asset and returns its body as text.
#
# `Invoke-WebRequest ... .Content` is only a string when PowerShell guesses a text
# content type. GitHub serves `oq.jar.sha256` as application/octet-stream, and then
# `.Content` is a byte[], which `[string]` renders as "System.Byte[]" and the checksum
# regex rejects. Reading the body off the response stream is content-type independent.
function Normalize-PathEntry([string]$Entry) {
    if ([string]::IsNullOrWhiteSpace($Entry)) { return "" }
    return $Entry.Trim().TrimEnd('\', '/')
}

function Test-PathContainsEntry([string]$PathValue, [string]$Entry) {
    $target = Normalize-PathEntry $Entry
    if ($target -eq "") { return $false }
    foreach ($part in ($PathValue -split ';')) {
        if ((Normalize-PathEntry $part) -ieq $target) { return $true }
    }
    return $false
}

function Add-UserPath([string]$Entry) {
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if (Test-PathContainsEntry $userPath $Entry) { return $false }
    if ([string]::IsNullOrEmpty($userPath)) {
        $newPath = $Entry
    } else {
        $newPath = "$userPath;$Entry"
    }
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    return $true
}

function Install-Oq {
    $installDir = if ($env:OQ_INSTALL_DIR) { $env:OQ_INSTALL_DIR } else { "$env:LOCALAPPDATA\oq" }
    $version = if ($env:OQ_VERSION) { $env:OQ_VERSION } else { "latest" }

    $null = Assert-Java21

    if ($version -eq "latest") {
        $tag = Get-LatestTag
        if (-not $tag) { Fail "Could not resolve the latest oq release (API and atom feed failed)." }
        $tag = Get-NormalizedTag $tag
    } else {
        $tag = Get-NormalizedTag $version
    }

    $jarUrl = "$script:TagBase/$tag/oq.jar"
    $shaUrl = "$script:TagBase/$tag/oq.jar.sha256"
    $sigUrl = "$script:TagBase/$tag/oq.jar.sigstore.json"

    Write-Info "Downloading oq $tag"
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null

    $jar = Join-Path $installDir "oq.jar"
    $launcher = Join-Path $installDir "oq.cmd"
    $tmp = Join-Path $installDir (".oq.jar.tmp.$PID")
    $bundle = Join-Path $installDir (".oq.jar.sigstore.json.$PID")
    $launcherTmp = Join-Path $installDir (".oq.cmd.tmp.$PID")

    try {
        try {
            Save-RemoteFile $jarUrl $tmp
        } catch {
            Fail "Download failed: $jarUrl"
        }

        try {
            $shaText = Get-RemoteText $shaUrl
        } catch {
            Fail "Missing checksum asset: $shaUrl"
        }
        $expected = Get-ExpectedSha ([string]$shaText)
        if (-not $expected) { Fail "Invalid checksum file at $shaUrl" }

        $actual = Get-Sha256 $tmp
        if ($actual -ne $expected) {
            Fail "Checksum mismatch for oq.jar (expected $expected, got $actual)."
        }

        # A checksum proves the download is intact, not that it came from us: whoever
        # can replace the jar can replace the checksum beside it. A signature closes
        # that gap. Older releases predate signing, so a missing bundle is a reported
        # state rather than a hard failure — but it is never called "verified", and
        # OQ_REQUIRE_SIGNATURE=1 turns it into a failure. This mirrors install.sh.
        $hasBundle = $false
        try {
            Save-RemoteFile $sigUrl $bundle
            # -Force is required: the bundle is a dotfile, and Get-Item refuses hidden
            # items without it. Without -Force the Length read threw, the catch below
            # swallowed it, and every signed release was reported as unsigned — the
            # verification silently never ran.
            $hasBundle = ((Test-Path -LiteralPath $bundle) -and ((Get-Item -LiteralPath $bundle -Force).Length -gt 0))
        } catch {
            $hasBundle = $false
        }

        if ($hasBundle) {
            $cosign = Get-CosignCommand
            if ($cosign) {
                if (-not (Test-Signature -Cosign $cosign -Bundle $bundle -Jar $tmp)) {
                    Fail "Signature verification FAILED for $jarUrl. The jar does not match its signed bundle; do not run it."
                }
                Write-Info "Signature verified (cosign, keyless signer)."
            } else {
                Write-Host "warning: oq $tag is signed, but 'cosign' is not installed so the signature was not checked." -ForegroundColor Yellow
                Write-Host "         The SHA-256 matched, which detects corruption and NOT tampering."
                Write-Host "         Install cosign (https://docs.sigstore.dev/cosign/installation/) to check it."
                if ($env:OQ_REQUIRE_SIGNATURE -eq "1") {
                    Fail "OQ_REQUIRE_SIGNATURE=1 but cosign is not installed."
                }
            }
        } else {
            Write-Host "warning: oq $tag has no published signature, only a SHA-256." -ForegroundColor Yellow
            Write-Host "         That detects corruption and NOT tampering: a replacement jar could ship with a"
            Write-Host "         replacement checksum. Releases from the next tag onward are signed."
            if ($env:OQ_REQUIRE_SIGNATURE -eq "1") {
                Fail "OQ_REQUIRE_SIGNATURE=1 but $tag publishes no signature ($sigUrl)."
            }
        }

        $smoke = (& $script:JavaBin -jar $tmp --version 2>&1 | Out-String)
        if ($LASTEXITCODE -ne 0) {
            Fail "Downloaded oq.jar failed to run (java -jar ... --version)."
        }
        $smokeFirst = ($smoke -split "`r?`n")[0]
        if (-not $smokeFirst.StartsWith("oq ")) {
            Fail "Downloaded oq.jar did not report an oq version (got: $smokeFirst)"
        }

        Move-Item -Force -Path $tmp -Destination $jar
        $tmp = $null

        $launcherText = "@echo off`r`njava -jar `"%~dp0oq.jar`" %*`r`n"
        Set-Content -Path $launcherTmp -Value $launcherText -Encoding ASCII -NoNewline
        Move-Item -Force -Path $launcherTmp -Destination $launcher
        $launcherTmp = $null
    } finally {
        if ($tmp -and (Test-Path -LiteralPath $tmp)) {
            Remove-Item -Force -LiteralPath $tmp -ErrorAction SilentlyContinue
        }
        if ($bundle -and (Test-Path -LiteralPath $bundle)) {
            Remove-Item -Force -LiteralPath $bundle -ErrorAction SilentlyContinue
        }
        if ($launcherTmp -and (Test-Path -LiteralPath $launcherTmp)) {
            Remove-Item -Force -LiteralPath $launcherTmp -ErrorAction SilentlyContinue
        }
    }

    Write-Info "Installed oq $tag to $launcher"
    if (Add-UserPath $installDir) {
        Write-Info "Added $installDir to your user PATH. Open a new terminal."
    }
    Write-Info "Try: oq --version"
}

# Run on direct execution; tests dot-source this file or set OQ_INSTALLER_LIB=1.
if ($MyInvocation.InvocationName -ne '.' -and $env:OQ_INSTALLER_LIB -ne "1") {
    try {
        Install-Oq
    } catch {
        Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
        exit 1
    }
}
