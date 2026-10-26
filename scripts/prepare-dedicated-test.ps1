$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$target = Join-Path $taskRoot 'build/run/edbDedicatedTest'
New-Item -ItemType Directory -Force -Path $target | Out-Null
@'
server-ip=127.0.0.1
server-port=0
online-mode=false
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
pause-when-empty-seconds=0
max-tick-time=10000
view-distance=3
simulation-distance=3
sync-chunk-writes=true
'@ | Set-Content -Encoding ascii -LiteralPath (Join-Path $target 'server.properties')
$consent = Join-Path $target 'eula.txt'
if (-not (Test-Path -LiteralPath $consent)) {
    "# Review https://aka.ms/MinecraftEULA before setting eula=true.`neula=false" | Set-Content -Encoding ascii -LiteralPath $consent
}
Write-Output "Prepared isolated server at $target. Accept the Minecraft EULA in eula.txt before running runEdbDedicatedTest."
