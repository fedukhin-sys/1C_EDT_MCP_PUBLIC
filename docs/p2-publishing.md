# Публикация p2-репозитория (установка/обновление плагина)

Плагин EDT MCP собирается в **p2-репозиторий** и публикуется как **update site**
на GitHub Pages. Пользователь добавляет один URL в 1C:EDT и ставит/обновляет плагин
штатным диалогом *Install New Software*.

## Почему self-hosted runner

Сборка Tycho требует **target platform** — локальный p2-пул установленной 1C:EDT
(`targets/default/default.target` → `<location ... type="Directory"/>`). Бандлы 1С
проприетарные и недоступны на облачных раннерах GitHub, поэтому сборка идёт на
**self-hosted раннере**, развёрнутом на машине с установленной 1C:EDT.

Важно: в сам p2-репозиторий бандлы 1С **не попадают** (`includeAllDependencies=false`
в `repositories/.../pom.xml`) — публикуются наши бандлы (feature целиком, 14 шт.:
12 tool-бандлов + `core` + `ui`) плюс сторонние зависимости (MCP SDK, Jetty, Jackson, …).
Публикуемый артефакт чист от IP 1С.

## Что делает workflow

`.github/workflows/publish-p2.yml`:

| Триггер | Действие |
|---|---|
| push тега `vX.Y.Z` | сборка → публикация в `gh-pages` (composite) → GitHub Release с ZIP |
| ручной запуск (`workflow_dispatch`) | сборка; публикация только при `publish=true` |

Структура `gh-pages`:

```
/                       composite-корень (compositeArtifacts.xml, compositeContent.xml) ← URL для EDT
/releases/1.15.5/       снимок версии (история сохраняется)
/releases/1.16.0/
/latest/                зеркало последней сборки
/index.html
```

## Разовая настройка

1. **Self-hosted runner.** На машине с 1C:EDT:
   *Repo → Settings → Actions → Runners → New self-hosted runner* (Windows).
   Назначить метки: `self-hosted`, `windows`, `edt`.
   На раннере должны быть: `git`, `gh` CLI. JDK 17 ставит сам workflow.
2. **GitHub Pages.** *Settings → Pages → Build and deployment → Source: Deploy from a branch*,
   branch = `gh-pages`, folder = `/ (root)`. (Ветка появится после первого релиза.)
3. **(Опц.) EDT_POOL_PATH.** Если раннер не на машине разработчика и пул лежит иначе —
   *Settings → Secrets and variables → Actions → Variables* → `EDT_POOL_PATH` =
   путь к `…/.p2/pool/plugins`. По умолчанию используется путь из `default.target`.

## Релиз

```pwsh
git tag v1.16.0
git push origin v1.16.0
```

Workflow соберёт и опубликует. Update site:

```
https://<owner>.github.io/<repo>/
```

## Локальная сборка

```pwsh
# свой пул через env (необязательно — по умолчанию путь из default.target)
$env:EDT_POOL_PATH = "C:/Users/User/.p2/pool/plugins"
./scripts/build-p2.ps1
# → repositories/ru.fedukhin.edt.mcp.repository/target/repository/
```

## Скрипты

| Файл | Назначение |
|---|---|
| `scripts/build-p2.ps1` | локальная сборка (+ опц. подмена пула, восстановление .target) |
| `scripts/set-edt-pool.ps1` | подмена пути к пулу в Directory-локации `.target` |
| `scripts/gen-composite-p2.ps1` | генерация composite XML над `releases/*` |
| `scripts/publish-pages.ps1` | публикация собранного p2 в ветку `gh-pages` |
