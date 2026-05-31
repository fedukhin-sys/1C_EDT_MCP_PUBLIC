<#
  Локальная сборка p2-репозитория EDT_MCP.

  Путь к p2-пулу 1C:EDT берётся из параметра -PoolPath или $env:EDT_POOL_PATH.
  Если не задан — используется путь, уже зашитый в targets/default/default.target
  (на машине разработчика это и есть рабочий путь, ничего подменять не нужно).

  Если override задан, .target подменяется на время сборки и затем
  восстанавливается через `git checkout`, чтобы не оставлять грязный рабочий стол.

  Результат: repositories/ru.fedukhin.edt.mcp.repository/target/repository/
#>
[CmdletBinding()]
param(
    [string]$PoolPath = $env:EDT_POOL_PATH,
    [switch]$RunTests
)
$ErrorActionPreference = 'Stop'

$repoRoot   = Split-Path -Parent $PSScriptRoot
$targetFile = Join-Path $repoRoot 'targets/default/default.target'

# Maven не в PATH (см. заметку maven-location)
$mvnBin = 'E:\Tools\maven\apache-maven-3.9.9\bin'
if (Test-Path $mvnBin) { $env:PATH = "$mvnBin;$env:PATH" }

$restore = $false
try {
    if ($PoolPath) {
        & (Join-Path $PSScriptRoot 'set-edt-pool.ps1') -TargetFile $targetFile -PoolPath $PoolPath
        $restore = $true
    }

    $mvnArgs = @('-B', 'clean', 'verify', '-Dtycho.localArtifacts=ignore')
    if (-not $RunTests) { $mvnArgs += '-DskipTests' }

    Push-Location $repoRoot
    try {
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) { throw "mvn завершился с кодом $LASTEXITCODE" }
    }
    finally { Pop-Location }

    $out = Join-Path $repoRoot 'repositories/ru.fedukhin.edt.mcp.repository/target/repository'
    Write-Host ""
    Write-Host "p2-репозиторий собран: $out"
}
finally {
    if ($restore) {
        git -C $repoRoot checkout -- $targetFile 2>$null
        Write-Host "default.target восстановлен"
    }
}
