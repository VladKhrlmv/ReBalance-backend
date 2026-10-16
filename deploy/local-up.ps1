<#
.SYNOPSIS
  Build backend jar + SPA from local sources and start the full stack on http://localhost:8088.

.EXAMPLE
  .\deploy\local-up.ps1               # build everything, then (re)start the stack
  .\deploy\local-up.ps1 -SkipBuild    # only rebuild images from existing target\ and build\
  .\deploy\local-up.ps1 -WebDir C:\src\ReBalanceWeb

  Requires: Docker Desktop (compose v2.24+), JDK 21, Node.js + npm.
  Expects the frontend repo next to this one: ReBalance\ReBalanceWeb.
  Works with Windows PowerShell 5.1 and PowerShell 7.
#>
param(
    [switch]$SkipBuild,
    [string]$WebDir = ""
)

$ErrorActionPreference = "Stop"

# Runs a native command and fails on a non-zero exit code. ErrorActionPreference is relaxed
# meanwhile because Windows PowerShell 5.1 can turn harmless stderr output (docker/npm
# progress) into terminating errors.
function Invoke-Checked {
    param([string]$What, [scriptblock]$Command)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Command } finally { $ErrorActionPreference = $prev }
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
}

# npm.cmd avoids npm.ps1, which a restrictive execution policy may block.
$OnWindows = ($env:OS -eq "Windows_NT")
$Npm = if ($OnWindows) { "npm.cmd" } else { "npm" }

$DeployDir  = $PSScriptRoot
$BackendDir = Split-Path -Parent $DeployDir
if ([string]::IsNullOrEmpty($WebDir)) { $WebDir = Join-Path (Split-Path -Parent $BackendDir) "ReBalanceWeb" }
if (-not (Test-Path (Join-Path $WebDir "package.json"))) { throw "Frontend repo not found at $WebDir (use -WebDir)" }
$WebDir = (Resolve-Path $WebDir).Path

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "docker not found" }

if (-not $SkipBuild) {
    Write-Host ">>> Building backend jar ($BackendDir)"
    Push-Location $BackendDir
    try {
        if ($OnWindows) { Invoke-Checked "Maven build" { & .\mvnw.cmd -B -DskipTests package } }
        else { Invoke-Checked "Maven build" { & sh ./mvnw -B -DskipTests package } }
    }
    finally { Pop-Location }

    Write-Host ">>> Building SPA ($WebDir)"
    Push-Location $WebDir
    $oldInline = $env:INLINE_RUNTIME_CHUNK; $oldMaps = $env:GENERATE_SOURCEMAP
    try {
        if (-not (Test-Path "node_modules")) { Invoke-Checked "npm ci" { & $Npm ci } }
        # Same build flags as CI: no inline runtime chunk (CSP script-src 'self'), no source maps.
        $env:INLINE_RUNTIME_CHUNK = "false"
        $env:GENERATE_SOURCEMAP = "false"
        Invoke-Checked "npm run build" { & $Npm run build }
    }
    finally {
        $env:INLINE_RUNTIME_CHUNK = $oldInline; $env:GENERATE_SOURCEMAP = $oldMaps
        Pop-Location
    }
}

if (-not (Get-ChildItem -Path (Join-Path $BackendDir "target") -Filter "*.jar" -ErrorAction SilentlyContinue)) {
    throw "No jar in $BackendDir\target - run without -SkipBuild"
}
if (-not (Test-Path (Join-Path $WebDir "build\index.html"))) {
    throw "No $WebDir\build\index.html - run without -SkipBuild"
}

Write-Host ">>> Starting stack"
Push-Location $DeployDir
try {
    $env:WEB_SRC_DIR = $WebDir
    Invoke-Checked "docker compose up" {
        & docker compose -f docker-compose.yml -f compose.local.yml --env-file .env.local up -d --build
    }
}
finally { Pop-Location }

$dc = "docker compose -f docker-compose.yml -f compose.local.yml --env-file .env.local"
Write-Host ""
Write-Host "ReBalance is starting on http://localhost:8088  (backend needs ~30-90 s on first start)"
Write-Host "  (run these from $DeployDir)"
Write-Host "  status : $dc ps"
Write-Host "  logs   : $dc logs -f backend"
Write-Host "  stop   : $dc down"
Write-Host "  reset  : $dc down -v"
