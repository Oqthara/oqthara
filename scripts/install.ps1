# Installs the oq CLI on Windows. Requires Java 21+.
#
# OQ_VERSION accepts "latest" (default), "0.1.0-alpha.2" or "v0.1.0-alpha.2".
# OQ_INSTALL_DIR defaults to "$env:LOCALAPPDATA\oq".
#
# Test seams (defaults are the real endpoints):
#   OQ_RELEASE_API       default https://api.github.com/repos/Oqthara/oqthara/releases?per_page=30
#   OQ_RELEASE_TAG_BASE  default https://github.com/Oqthara/oqthara/releases/download
#   OQ_JAVA              default java
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

function Get-LatestTag {
    try {
        $response = Invoke-WebRequest -Uri $script:ReleaseApi -UseBasicParsing -TimeoutSec 30
        $releases = @($response.Content | ConvertFrom-Json)
        foreach ($release in $releases) {
            if ($release.draft) { continue }
            $tag = [string]$release.tag_name
            if ($tag) { return $tag }
        }
    } catch {
        # fall through to the atom feed
    }
    try {
        $atom = (Invoke-WebRequest -Uri $script:AtomUrl -UseBasicParsing -TimeoutSec 30).Content
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

function Save-RemoteFile([string]$Uri, [string]$OutFile) {
    Invoke-WebRequest -Uri $Uri -UseBasicParsing -OutFile $OutFile -TimeoutSec 300
}

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

    Write-Info "Downloading oq $tag"
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null

    $jar = Join-Path $installDir "oq.jar"
    $launcher = Join-Path $installDir "oq.cmd"
    $tmp = Join-Path $installDir (".oq.jar.tmp.$PID")
    $launcherTmp = Join-Path $installDir (".oq.cmd.tmp.$PID")

    try {
        try {
            Save-RemoteFile $jarUrl $tmp
        } catch {
            Fail "Download failed: $jarUrl"
        }

        try {
            $shaText = (Invoke-WebRequest -Uri $shaUrl -UseBasicParsing -TimeoutSec 60).Content
        } catch {
            Fail "Missing checksum asset: $shaUrl"
        }
        $expected = Get-ExpectedSha ([string]$shaText)
        if (-not $expected) { Fail "Invalid checksum file at $shaUrl" }

        $actual = Get-Sha256 $tmp
        if ($actual -ne $expected) {
            Fail "Checksum mismatch for oq.jar (expected $expected, got $actual)."
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
