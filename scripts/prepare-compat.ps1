param([string[]]$Mods = @())
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$lock = Get-Content -LiteralPath (Join-Path $taskRoot 'tests/compatibility-lock.json') -Raw | ConvertFrom-Json
$target = Join-Path $taskRoot 'build/compat-mods'
New-Item -ItemType Directory -Force -Path $target | Out-Null
foreach ($property in $lock.mods.PSObject.Properties) {
    if ($Mods.Count -gt 0 -and $property.Name -notin $Mods) { continue }
    $mod = $property.Value
    $path = Join-Path $target $mod.filename
    if (-not (Test-Path -LiteralPath $path)) {
        Invoke-WebRequest -Uri $mod.url -OutFile $path
    }
    $hash = (Get-FileHash -LiteralPath $path -Algorithm $mod.hashAlgorithm).Hash
    if ($hash -ne $mod.hash) { throw "Hash mismatch: $($mod.filename)" }
    Write-Output "$($property.Name): $($mod.version) verified"
}
