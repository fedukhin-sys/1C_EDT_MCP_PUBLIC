<#
  Собирает «пул одной установки» 1C:EDT для target platform.

  Зачем. Target-файл берёт бандлы Directory-локацией из общего p2-пула
  (~/.p2/pool/plugins). Пока в пуле жила одна версия EDT, этого хватало. После
  установки второй (2026.1 + 2026.2) в пуле оказались два набора бандлов с разными
  версиями библиотек (например, Guava 32.1.3 и 33.5.0): компиляция проходит, а
  тестовый OSGi-рантайм Tycho не стартует — uses constraint violation на
  com.google.common.base. Tycho не умеет брать target из bundles.info установки
  (Profile/Directory-локации просто сканируют каталог), поэтому каталог с ровно
  нужным набором бандлов собираем сами.

  Как. Читает configuration/org.eclipse.equinox.simpleconfigurator/bundles.info
  установки и кладёт в <OutDir>/plugins жёсткую ссылку на каждый jar и junction на
  каждый бандл-каталог. Жёсткие ссылки не занимают места, но требуют одного тома
  с пулом — поэтому OutDir по умолчанию рядом с пулом. Прав администратора не нужно.

  Пример:
    ./scripts/make-edt-target-pool.ps1 `
        -Installation "$env:LOCALAPPDATA/1C/1cedtstart/installations/1C_EDT 2026.1 (1)/1cedt" `
        -OutDir "$env:USERPROFILE/.p2/edt-target-2026.1"
    ./scripts/set-edt-pool.ps1 -TargetFile targets/default/default.target `
        -PoolPath "$env:USERPROFILE/.p2/edt-target-2026.1/plugins"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Installation,
    [Parameter(Mandatory)][string]$OutDir
)
$ErrorActionPreference = 'Stop'

$bundlesInfo = Join-Path $Installation 'configuration/org.eclipse.equinox.simpleconfigurator/bundles.info'
if (-not (Test-Path -LiteralPath $bundlesInfo)) {
    throw "bundles.info не найден: $bundlesInfo"
}

$plugins = Join-Path $OutDir 'plugins'
if (Test-Path -LiteralPath $plugins) {
    # Пересборка с нуля: старые ссылки могли остаться от прошлой версии EDT.
    # Junction удаляем через rmdir, иначе PowerShell 5.1 уходит внутрь цели.
    Get-ChildItem -LiteralPath $plugins -Force | ForEach-Object {
        if ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            cmd /c rmdir "$($_.FullName)" | Out-Null
        } else {
            Remove-Item -LiteralPath $_.FullName -Force
        }
    }
} else {
    New-Item -ItemType Directory -Force -Path $plugins | Out-Null
}

$linked = 0
$missing = New-Object System.Collections.Generic.List[string]
foreach ($line in [IO.File]::ReadAllLines($bundlesInfo)) {
    if ($line.StartsWith('#') -or [string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line.Split(',')
    if ($parts.Length -lt 3) { continue }
    $location = $parts[2]

    if ($location -match '^file:') {
        $source = ([Uri]$location).LocalPath
    } else {
        # Относительный путь — от каталога установки.
        $source = Join-Path $Installation $location
    }
    $source = $source.TrimEnd('\', '/')
    if (-not (Test-Path -LiteralPath $source)) {
        $missing.Add($source)
        continue
    }

    $link = Join-Path $plugins (Split-Path -Leaf $source)
    if (Test-Path -LiteralPath $link) { continue }
    if ((Get-Item -LiteralPath $source).PSIsContainer) {
        New-Item -ItemType Junction -Path $link -Target $source | Out-Null
    } else {
        New-Item -ItemType HardLink -Path $link -Target $source | Out-Null
    }
    $linked++
}

Write-Host "Бандлов в пуле установки: $linked -> $plugins"
if ($missing.Count -gt 0) {
    Write-Warning ("В bundles.info есть бандлы, которых нет на диске ({0}):`n  {1}" -f $missing.Count, ($missing -join "`n  "))
}
