# EDT_MCP

MCP-сервер для 1C:EDT — **v1.15.1** (workspace, проекты, модули BSL, инфобазы, деплой, запуск клиента, отладка, проверки качества, редактирование метаданных, авторство форм, xUnitFor1C, схемы СКД, журнал регистрации).

Поднимает Bearer-защищённый HTTP+SSE MCP-сервер внутри 1C:EDT с **89 инструментами** для управления workspace'ом, проектами, модулями BSL, информационными базами (включая deploy), запуском клиента 1С, сессиями отладки, проверками качества, метаданными (CRUD + editor для 11 видов объектов и табличных частей), формами (создание + UI-элементы), схемами компоновки данных (`.dcs`), журналом регистрации и xUnitFor1C (создание модулей + auto-run).

UI: страница Preferences, статус-бар, команды Start/Stop/Restart в меню **Window → EDT MCP**.

---

## Установка из update site (рекомендуется)

Готовый p2-репозиторий публикуется на GitHub Pages — собирать из исходников не нужно.

В 1C:EDT: **Help → Install New Software → Add…**, в поле *Location* вставить:

```
https://fedukhin-sys.github.io/1C_EDT_MCP_PUBLIC/
```

Отметить **EDT MCP** → Next → принять лицензию (Apache 2.0) → Finish → рестарт IDE.
Обновление позже — тем же диалогом (**Check for Updates**): EDT подтянет свежую версию.

Альтернатива (офлайн): скачать ZIP из [Releases](https://github.com/fedukhin-sys/1C_EDT_MCP_PUBLIC/releases) и **Add → Archive…**.

После установки — сразу к шагу [5. Настроить и запустить](#5-настроить-и-запустить).

> Как этот update site собирается и публикуется (self-hosted runner + Pages) — см. [`docs/p2-publishing.md`](docs/p2-publishing.md).

---

## Быстрый старт с нуля

Пошаговый сценарий: от пустой машины до первого вызова MCP-инструмента из Claude Code / Claude Desktop / любого MCP-клиента.

### 1. Установить окружение

- **1C:EDT 2026.1** или новее (с поддержкой 1С:Предприятие 8.3.27). [edt.1c.ru](https://edt.1c.ru/).
- **JDK 17** (для сборки плагина из исходников). 1C:EDT приходит со своим JDK — его можно использовать.
- **Maven 3.9+** — для сборки. На Windows необязательно добавлять в `PATH`, можно вызывать `bin/mvn.cmd` по абсолютному пути.
- **Git** — клонировать репозиторий.

### 2. Получить исходники

```bash
git clone https://github.com/fedukhin-sys/1C_EDT_MCP_PUBLIC.git
cd 1C_EDT_MCP_PUBLIC
```

### 3. Собрать p2-репозиторий

Tycho тянет target platform из локально установленного 1C:EDT (пул p2 в `C:/Users/<user>/.p2/pool/plugins` на Windows, аналогично на других ОС — см. `targets/default/default.target`):

```bash
mvn clean verify
```

После сборки готовый p2-сайт лежит в:

```
repositories/ru.fedukhin.edt.mcp.repository/target/repository/
```

Это локальный URL, который понадобится на следующем шаге.

### 4. Установить плагин в 1C:EDT

В IDE: **Help → Install New Software → Add → Local…**, указать путь на собранный `repository/`. В списке появится **EDT MCP** — отметить, Next, принять лицензию (Apache 2.0), Finish, рестарт IDE.

После рестарта в нижнем правом углу появится статус-бар `MCP: stopped`.

### 5. Настроить и запустить

**Window → Preferences → EDT MCP**:
- **Port** — порт SSE (по умолчанию `3001`);
- **Auto-start on IDE launch** — автозапуск при старте IDE (по умолчанию выкл);
- **Bearer token** — read-only, нажать **Regenerate token** для генерации первого токена и скопировать значение в безопасное место (например `~/.edt-mcp-token`).

Запустить: **Window → EDT MCP → Start** (или клик по статус-бару). Статус становится `MCP :3001 ●` (зелёный).

### 6. Проверить, что работает

Через `curl` (любой MCP-клиент достаточно, чтобы убедиться что сервер отдаёт SSE-handshake):

```bash
curl -N -H "Authorization: Bearer <token>" http://127.0.0.1:3001/mcp/sse
```

Ожидаем первое событие `event: endpoint` с URL для POST-сообщений. Без токена — `401 Unauthorized`.

Через MCP Inspector:

```bash
npx @modelcontextprotocol/inspector --transport sse \
    --url http://127.0.0.1:3001/mcp/sse \
    --header "Authorization: Bearer <token>"
```

В разделе Tools должно быть **89 инструментов**.

### 7. Подключить из MCP-клиента

#### Claude Code

```bash
claude mcp add edt-mcp \
    --transport sse \
    --url http://127.0.0.1:3001/mcp/sse \
    --header "Authorization: Bearer <token>"
```

После этого `claude` в любой папке видит `edt-mcp` как набор тулзов. Проверить: спросить `какие проекты открыты в EDT?` — Claude вызовет `list_projects`.

#### Claude Desktop

В `~/.config/claude/claude_desktop_config.json` (или `%APPDATA%\Claude\claude_desktop_config.json` на Windows):

```json
{
  "mcpServers": {
    "edt-mcp": {
      "transport": "sse",
      "url": "http://127.0.0.1:3001/mcp/sse",
      "headers": {
        "Authorization": "Bearer <token>"
      }
    }
  }
}
```

Рестарт Claude Desktop — иконка молотка покажет доступные инструменты.

#### Свой MCP-клиент / smoke-тест

Минимальный JS-клиент описан в [скилле `edt-mcp`](.claude/skills/edt-mcp/SKILL.md#smoke-harness). Подключение: SSE на `/mcp/sse` → ловим `event: endpoint` → POST в `/mcp/messages?sessionId=…` с JSON-RPC. Авторизация — `Authorization: Bearer <token>` в каждом запросе.

### Что делать дальше

- Полный каталог инструментов с описанием — [`docs/tools.md`](docs/tools.md).
- Практический справочник по работе из MCP-клиента (рецепты, известные баги, workaround'ы) — [`.claude/skills/edt-mcp/SKILL.md`](.claude/skills/edt-mcp/SKILL.md).
- Паттерны построения отчётов на СКД — [`.claude/skills/edt-skd/SKILL.md`](.claude/skills/edt-skd/SKILL.md).

---

## Статус-бар и управление сервером

- `MCP: stopped` / `MCP: starting` — серый;
- `MCP :<port> ●` — зелёный;
- `MCP: error` — красный, tooltip содержит причину.

Клик по статус-итему открывает страницу Preferences. В меню `Window → EDT MCP` доступны явные команды **Start / Stop / Restart**. Смена порта в Preferences автоматически перезапускает сервер.

## MCP tools

Полный актуальный список — [`docs/tools.md`](docs/tools.md). Группировка:

| Bundle | Назначение | Кол-во |
|---|---|---|
| `tools.edt` | Workspace и проекты | 8 |
| `tools.bsl` | Модули BSL | 5 |
| `tools.infobase` | Информационные базы и развёртывание | 5 |
| `tools.eventlog` | Журнал регистрации | 2 |
| `tools.client` | Запуск клиента 1С | 3 |
| `tools.debug` | Отладка BSL | 14 |
| `tools.quality` | Проверки качества кода | 4 |
| `tools.md` | Редактирование метаданных | 29 |
| `tools.form` | Формы | 11 |
| `tools.tests` | Каркас тестов xUnitFor1C | 4 |
| `tools.testrun` | Запуск тестов xUnit | 4 |
| **Итого** | | **89** |

## Build

См. [`CONTRIBUTING.md`](CONTRIBUTING.md) — требования к окружению, Maven/Tycho-сборка, тесты, релизный процесс.

## Лицензия

Apache License 2.0.
