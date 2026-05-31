<#
  Публикует собранный p2-репозиторий в ветку gh-pages.

  - releases/<Version>/  — снимок этой версии (история сохраняется)
  - latest/              — зеркало последней сборки (стабильный URL «всегда свежее»)
  - compositeArtifacts.xml / compositeContent.xml — composite-корень над releases/*
  - index.html, .nojekyll — служебные

  Запускается из CI (GitHub Actions). Авторизация — через GITHUB_TOKEN.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Version,    # напр. 1.15.5
    [Parameter(Mandatory)][string]$RepoSlug,   # owner/repo
    [Parameter(Mandatory)][string]$Token       # GITHUB_TOKEN
)
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$src      = Join-Path $repoRoot 'repositories/ru.fedukhin.edt.mcp.repository/target/repository'
if (-not (Test-Path $src)) { throw "p2-репозиторий не найден: $src (сначала собери)" }

$owner    = ($RepoSlug -split '/')[0]
$repoName = ($RepoSlug -split '/')[1]
$pagesUrl = "https://$owner.github.io/$repoName"

$workBase = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { $env:TEMP }
$work     = Join-Path $workBase 'gh-pages-pub'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }

$pushUrl = "https://x-access-token:$Token@github.com/$RepoSlug.git"

# Клонируем существующую gh-pages; если ветки нет — создаём orphan
git clone --quiet --branch gh-pages --single-branch $pushUrl $work 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "ветка gh-pages отсутствует — создаю orphan"
    git clone --quiet $pushUrl $work
    Push-Location $work
    git checkout --orphan gh-pages
    git rm -rf . 2>$null | Out-Null
    Pop-Location
}

# releases/<Version>/
$relDir = Join-Path $work "releases/$Version"
if (Test-Path $relDir) { Remove-Item -Recurse -Force $relDir }
New-Item -ItemType Directory -Force -Path $relDir | Out-Null
Copy-Item -Recurse -Force "$src/*" $relDir

# latest/
$latest = Join-Path $work 'latest'
if (Test-Path $latest) { Remove-Item -Recurse -Force $latest }
New-Item -ItemType Directory -Force -Path $latest | Out-Null
Copy-Item -Recurse -Force "$src/*" $latest

# composite-корень
& (Join-Path $PSScriptRoot 'gen-composite-p2.ps1') -SiteRoot $work

# служебные файлы
New-Item -ItemType File -Force -Path (Join-Path $work '.nojekyll') | Out-Null
$enc = New-Object System.Text.UTF8Encoding $false
$index = @"
<!doctype html><html lang="ru"><meta charset="utf-8">
<title>EDT MCP - p2 update site</title>
<body style="font-family:sans-serif;max-width:720px;margin:3rem auto;line-height:1.5">
<h1>EDT MCP - p2 update site</h1>
<p>Это p2-репозиторий для установки и обновления плагина <b>EDT MCP</b> в 1C:EDT.</p>
<h2>Установка</h2>
<ol>
<li>1C:EDT - <b>Help - Install New Software</b></li>
<li><b>Add - </b> в поле <i>Location</i> вставить:<br>
<code>$pagesUrl/</code></li>
<li>Отметить <b>EDT MCP</b> - Next - принять лицензию - Finish - перезапуск.</li>
</ol>
<p>Обновление - тем же диалогом (<b>Check for Updates</b>); EDT подтянет свежую версию из composite-репозитория.</p>
<p>Последняя сборка отдельно: <code>$pagesUrl/latest/</code></p>
</body></html>
"@
[System.IO.File]::WriteAllText((Join-Path $work 'index.html'), $index, $enc)

Push-Location $work
try {
    git add -A
    # пустой коммит не делаем
    git -c user.email="github-actions[bot]@users.noreply.github.com" `
        -c user.name="github-actions[bot]" `
        commit -m "p2: publish $Version" 2>$null
    if ($LASTEXITCODE -ne 0) { Write-Host "нет изменений для коммита"; return }
    git push --quiet $pushUrl HEAD:gh-pages
    if ($LASTEXITCODE -ne 0) { throw "git push в gh-pages не удался" }
}
finally { Pop-Location }

Write-Host ""
Write-Host "Опубликовано. Update site: $pagesUrl/"
