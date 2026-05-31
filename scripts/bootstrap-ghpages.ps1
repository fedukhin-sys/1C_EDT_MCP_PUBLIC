<#
  Разовый бутстрап ветки gh-pages «с нуля»: свежий каталог, git init,
  один коммит, push в gh-pages. Без clone/orphan — надёжнее для первого раза.

  Дальнейшие публикации идут через publish-pages.ps1 (он уже клонирует
  существующую gh-pages и докидывает новый release).
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$RepoSlug,
    [Parameter(Mandatory)][string]$Token,
    [string]$Src = "E:\EDTProjects\EDT_MCP\repositories\ru.fedukhin.edt.mcp.repository\target\repository"
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Src)) { throw "p2 repo not found: $Src" }

$owner   = ($RepoSlug -split '/')[0]
$repo    = ($RepoSlug -split '/')[1]
$pushUrl = "https://x-access-token:$Token@github.com/$RepoSlug.git"
$mask    = [regex]::Escape($Token)

$work = Join-Path ([System.IO.Path]::GetTempPath()) "ghpages-bootstrap"
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null

# содержимое сайта
$rel = Join-Path $work "releases\$Version"
New-Item -ItemType Directory -Force $rel | Out-Null
Copy-Item -Recurse -Force (Join-Path $Src '*') $rel
$lat = Join-Path $work "latest"
New-Item -ItemType Directory -Force $lat | Out-Null
Copy-Item -Recurse -Force (Join-Path $Src '*') $lat

& (Join-Path $PSScriptRoot 'gen-composite-p2.ps1') -SiteRoot $work
New-Item -ItemType File -Force (Join-Path $work '.nojekyll') | Out-Null

$pagesUrl = "https://$owner.github.io/$repo"
$enc = New-Object System.Text.UTF8Encoding $false
$index = "<!doctype html><html lang=`"ru`"><meta charset=`"utf-8`"><title>EDT MCP - p2 update site</title><body style=`"font-family:sans-serif;max-width:700px;margin:3rem auto;line-height:1.5`"><h1>EDT MCP - p2 update site</h1><p>1C:EDT: Help &rarr; Install New Software &rarr; Add &rarr; в поле Location:</p><p><code>$pagesUrl/</code></p><p>Только последняя сборка: <code>$pagesUrl/latest/</code></p></body></html>"
[System.IO.File]::WriteAllText((Join-Path $work 'index.html'), $index, $enc)

Push-Location $work
try {
    git init -q
    git checkout -q -b gh-pages
    git add -A
    git -c user.name="fedukhin-sys" -c user.email="fedukhinai@gmail.com" commit -q -m "p2: publish $Version (bootstrap update site)"
    Write-Host "committed; pushing gh-pages..."
    $out = (& git push -u $pushUrl gh-pages:gh-pages 2>&1 | Out-String)
    Write-Host ($out -replace $mask, '***')
    if ($LASTEXITCODE -ne 0) { throw "push failed (exit $LASTEXITCODE)" }
}
finally { Pop-Location }
Write-Host "gh-pages опубликован. Update site: $pagesUrl/"
