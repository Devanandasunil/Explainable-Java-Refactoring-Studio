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
if (Test-Path (Join-Path $jdk "bin\java.exe")) { $env:JAVA_HOME = $jdk; $env:Path = "$jdk\bin;$env:Path" }
if (-not (Test-Path $maven)) { $maven = "mvn" }
$env:STUDIO_MAVEN_CMD = $maven
$env:STUDIO_MAVEN_REPO = $cache
& $maven "-f" (Join-Path $project "pom.xml") "-Dmaven.repo.local=$cache" "verify"
if ($LASTEXITCODE -ne 0) { throw "Verification failed. Review the test, PMD, and JaCoCo output above." }
