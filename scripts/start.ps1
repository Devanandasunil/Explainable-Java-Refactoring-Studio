$ErrorActionPreference = "Stop"
$project = Split-Path -Parent $PSScriptRoot
$usbRoot = Split-Path -Parent $project
$portableJdk = Join-Path $usbRoot "tools\jdk-21.0.12.1+1\bin\java.exe"
$portableMaven = Join-Path $usbRoot "tools\apache-maven-3.10.0\bin\mvn.cmd"
if (-not (Test-Path $portableJdk) -and -not (Test-Path $portableMaven)) {
  $usbRoot = Split-Path -Parent $usbRoot
}
$jdk = Join-Path $usbRoot "tools\jdk-21.0.12.1+1"
$maven = Join-Path $usbRoot "tools\apache-maven-3.10.0\bin\mvn.cmd"
$cache = Join-Path $usbRoot "cache\maven-repository"
if (Test-Path (Join-Path $jdk "bin\java.exe")) {
  $env:JAVA_HOME = $jdk
  $env:Path = "$jdk\bin;$env:Path"
} elseif (-not $env:JAVA_HOME) { throw "JDK not found. Edit this script for your USB layout." }
if (-not (Test-Path $maven)) { $maven = "mvn" }
New-Item -ItemType Directory -Force -Path $cache | Out-Null
$env:STUDIO_MAVEN_CMD = $maven
$env:STUDIO_MAVEN_REPO = $cache
Write-Host "Building Refactor Studio. First build may download dependencies..." -ForegroundColor Cyan
& $maven "-f" (Join-Path $project "pom.xml") "-Dmaven.repo.local=$cache" "test" "exec:java" "-Dexec.mainClass=com.semanticrefactoring.StudioServer"
if ($LASTEXITCODE -ne 0) { throw "Maven failed. Review the error above." }
