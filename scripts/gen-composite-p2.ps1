<#
  Генерирует composite p2-репозиторий (compositeArtifacts.xml + compositeContent.xml)
  в корне site, ссылаясь на ВСЕ подкаталоги releases/*.

  Composite-корень — это стабильный URL, который пользователь добавляет в
  1C:EDT (Install New Software). EDT видит объединение всех версий и предлагает
  установку/обновление до самой свежей.

  Каталог latest/ НЕ включается в composite (это отдельное зеркало последней
  сборки), иначе IU дублировались бы.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SiteRoot
)
$ErrorActionPreference = 'Stop'

$relRoot  = Join-Path $SiteRoot 'releases'
$children = @()
if (Test-Path $relRoot) {
    $children = Get-ChildItem -Path $relRoot -Directory |
                Sort-Object Name |
                ForEach-Object { "releases/$($_.Name)" }
}
$n        = $children.Count
$ts       = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$childXml = ($children | ForEach-Object { "    <child location='$_'/>" }) -join "`n"

$artifacts = @"
<?xml version='1.0' encoding='UTF-8'?>
<?compositeArtifactRepository version='1.0.0'?>
<repository name='EDT MCP - p2 (composite)' type='org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository' version='1.0.0'>
  <properties size='1'>
    <property name='p2.timestamp' value='$ts'/>
  </properties>
  <children size='$n'>
$childXml
  </children>
</repository>
"@

$content = @"
<?xml version='1.0' encoding='UTF-8'?>
<?compositeMetadataRepository version='1.0.0'?>
<repository name='EDT MCP - p2 (composite)' type='org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository' version='1.0.0'>
  <properties size='1'>
    <property name='p2.timestamp' value='$ts'/>
  </properties>
  <children size='$n'>
$childXml
  </children>
</repository>
"@

$enc = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText((Join-Path $SiteRoot 'compositeArtifacts.xml'), $artifacts, $enc)
[System.IO.File]::WriteAllText((Join-Path $SiteRoot 'compositeContent.xml'),  $content,   $enc)
Write-Host "composite обновлён: $n релиз(ов) -> $($children -join ', ')"
