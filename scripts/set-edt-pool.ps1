<#
  Подменяет путь к p2-пулу 1C:EDT в Directory-локации target-файла.

  Используется и в CI (self-hosted runner), и локально через build-p2.ps1.
  В .target правится ТОЛЬКО локация с type="Directory" — остальные
  (InstallableUnit / Maven) не трогаются.

  Пример:
    ./scripts/set-edt-pool.ps1 -TargetFile targets/default/default.target `
                               -PoolPath "D:/edt/.p2/pool/plugins"
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$TargetFile,
    [Parameter(Mandatory)][string]$PoolPath
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $TargetFile)) {
    throw "target-файл не найден: $TargetFile"
}

# p2 любит прямые слэши; нормализуем на всякий случай
$pool = $PoolPath -replace '\\', '/'

$content = Get-Content -Raw -LiteralPath $TargetFile
$pattern = '(<location\s+path=")[^"]*("\s+type="Directory"\s*/>)'
if ($content -notmatch $pattern) {
    throw "В $TargetFile не найдена Directory-локация (<location path=... type=`"Directory`"/>)"
}
$new = [regex]::Replace($content, $pattern, "`${1}$pool`${2}")

# UTF-8 без BOM — чтобы не плодить различий и не ломать XML-парсер
[System.IO.File]::WriteAllText($TargetFile, $new, (New-Object System.Text.UTF8Encoding $false))
Write-Host "EDT pool -> $pool  ($TargetFile)"
