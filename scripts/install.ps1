# Installs the oq CLI on Windows. Requires Java 21+.
$ErrorActionPreference = "Stop"

$repo = "Oqthara/oqthara"
$installDir = if ($env:OQ_INSTALL_DIR) { $env:OQ_INSTALL_DIR } else { "$env:LOCALAPPDATA\oq" }
$version = if ($env:OQ_VERSION) { $env:OQ_VERSION } else { "latest" }

function Fail($message) {
    Write-Host "error: $message" -ForegroundColor Red
    exit 1
}

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { Fail "Java is required. Install JDK 21 or newer." }

$javaVersion = (java -version 2>&1 | Select-Object -First 1) -replace '.*"([0-9]+).*', '$1'
if ([int]$javaVersion -lt 21) { Fail "Java 21 or newer is required (found $javaVersion)." }

if ($version -eq "latest") {
    $url = "https://github.com/$repo/releases/latest/download/oq.jar"
} else {
    $url = "https://github.com/$repo/releases/download/v$version/oq.jar"
}

New-Item -ItemType Directory -Force -Path $installDir | Out-Null
Write-Host "Downloading $url"
$jar = Join-Path $installDir "oq.jar"
Invoke-WebRequest -Uri $url -OutFile $jar

$cmd = Join-Path $installDir "oq.cmd"
@"
@echo off
java -jar "%~dp0oq.jar" %*
"@ | Set-Content -Path $cmd -Encoding ASCII

Write-Host "Installed to $cmd"
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notlike "*$installDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$installDir", "User")
    Write-Host "Added $installDir to your user PATH. Open a new terminal."
}
Write-Host "Try: oq --version"
