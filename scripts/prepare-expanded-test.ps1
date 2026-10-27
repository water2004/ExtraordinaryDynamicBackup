# Isolated fixture configuration; never touches an Orbit instance or production world.
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
foreach ($run in @('gameTest', 'edbDedicatedTest')) {
    $config = Join-Path $taskRoot "build/run/$run/config"
    foreach ($directory in @('voicechat', 'bluemap')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $config $directory) | Out-Null
    }
    $squaremap = Join-Path $taskRoot "build/run/$run/squaremap"
    New-Item -ItemType Directory -Force -Path $squaremap | Out-Null
    "bind_address=127.0.0.1`nport=0`nforce_voice_chat=false" | Set-Content -Encoding ascii -LiteralPath (Join-Path $config 'voicechat/voicechat-server.properties')
    @'
settings:
  update-checker: false
  internal-webserver:
    enabled: false
    bind: 127.0.0.1
'@ | Set-Content -Encoding ascii -LiteralPath (Join-Path $squaremap 'config.yml')
    # EULA acceptance is supplied separately by the operator, as for the dedicated server.
    $blueMapCore = Join-Path $config 'bluemap/core.conf'
    if (-not (Test-Path -LiteralPath $blueMapCore)) {
        @'
accept-download: false
data: "bluemap"
render-thread-count: 1
full-update-interval: 0
region-file-check-interval: 0
metrics: false
'@ | Set-Content -Encoding ascii -LiteralPath $blueMapCore
    }
    'enabled: false' | Set-Content -Encoding ascii -LiteralPath (Join-Path $config 'bluemap/webserver.conf')
    @'
disabled = false
enableAsyncSpawn = true
enableAsyncRandomTicks = false
'@ | Set-Content -Encoding ascii -LiteralPath (Join-Path $config 'async.toml')
}
Write-Output 'Prepared isolated compatibility fixtures with loopback voice and disabled map web servers.'
