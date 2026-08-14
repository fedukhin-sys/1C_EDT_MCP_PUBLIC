# EDT_MCP

MCP-сервер для 1C:EDT — **v1.22.0** (workspace, проекты, модули BSL, инфобазы, деплой, запуск клиента, отладка, проверки качества, редактирование метаданных, авторство форм, xUnitFor1C, схемы СКД, журнал регистрации, обезличивание ПДн 152-ФЗ).

Поднимает Bearer-защищённый HTTP+SSE MCP-сервер внутри 1C:EDT с **100 инструментами** для управления workspace'ом, проектами, модулями BSL, информационными базами (включая deploy), запуском клиента 1С, сессиями отладки, проверками качества, метаданными (CRUD + editor для 11 видов объектов и табличных частей), формами (создание + UI-элементы), схемами компоновки данных (`.dcs`), журналом регистрации, xUnitFor1C (создание модулей + auto-run) и обезличиванием персональных данных по 152-ФЗ (fail-closed слой перед отправкой данных ИБ клиенту).

UI: страница Preferences, статус-бар, команды Start/Stop/Restart в меню **Window → EDT MCP**.

---

## Быстрый старт

От установки плагина до первого вызова MCP-инструмента из Claude Code / Claude Desktop / любого MCP-клиента. Сборка из исходников не нужна — плагин ставится из готового update site (для разработки из исходников см. раздел [Сборка из исходников](#сборка-из-исходников)).

### 1. Установить 1C:EDT

**1C:EDT 2026.x** (рекомендуется, основная среда разработки плагина) — [edt.1c.ru](https://edt.1c.ru/).
Ветки 2023.x–2025.x поддерживаются dual-version-кодом, см. [матрицу](#матрица-поддержки-1cedt).
Больше ничего ставить не нужно: JDK, Maven и сборка требуются только при работе из исходников.

#### Матрица поддержки 1C:EDT

Плагин собирается против API EDT 2026.x, но расходящиеся места платформенного API
(`IInfobaseSynchronizationManager.resolveInfobaseChanges`, `.isConnected`, `.updateInfobase`)
вызываются через рефлексию с фолбэком — то есть один и тот же артефакт рассчитан на обе ветки.

| Ветка EDT | `dt.platform.services.core` | Статус |
|---|---|---|
| 2026.x | 21, 23 | Поддерживается, проверено live-smoke (основная среда разработки) |
| 2025.x, 2024.x, 2023.x | 18, 19 | Ожидается работоспособной, **live-smoke не проводился** |

Про ветку ≤2025.1: до v1.18.0 `deploy_project` был на ней неработоспособен —
`isConnected` и `resolveInfobaseChanges` отсутствуют/расходятся по сигнатуре в core 18/19
и давали `NoSuchMethodError` / `ClassCastException`. Обе точки закрыты рефлексией в v1.18.0,
но подтверждения на живой 2023.x пока нет — если вы работаете на этой ветке, рассчитывайте
на неё как на «ожидается, не проверялось».

Целевая платформа собирается из локально установленного EDT (см. `targets/default/default.target`).

### 2. Установить плагин из update site

В 1C:EDT: **Help → Install New Software → Add…**, в поле *Location* вставить URL и нажать **Add**:

```
https://fedukhin-sys.github.io/1C_EDT_MCP_PUBLIC/
```

Отметить **EDT MCP** → Next (EDT сам разрешит зависимости) → принять лицензию (Apache 2.0) → Finish → рестарт IDE.

После рестарта в правом нижнем углу появится статус-бар `MCP: stopped`.

Обновление позже — тем же диалогом (**Help → Check for Updates**): EDT подтянет свежую версию из того же URL.

> Альтернатива (офлайн): скачать ZIP из [Releases](https://github.com/fedukhin-sys/1C_EDT_MCP_PUBLIC/releases) и **Add → Archive…**.

### 3. Настроить и запустить

**Window → Preferences → EDT MCP**:
- **Port** — порт SSE (по умолчанию `3001`);
- **Auto-start on IDE launch** — автозапуск при старте IDE (по умолчанию выкл);
- **Bearer token** — read-only, нажать **Regenerate token** для генерации первого токена и скопировать значение в безопасное место (например `~/.edt-mcp-token`).

Запустить: **Window → EDT MCP → Start** (или клик по статус-бару). Статус становится `MCP :3001 ●` (зелёный).

### 4. Проверить, что работает

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

В разделе Tools должно быть **100 инструментов**.

### 5. Подключить из MCP-клиента

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
| `tools.client` | Запуск клиента 1С | 5 |
| `tools.debug` | Отладка BSL | 14 |
| `tools.quality` | Проверки качества кода | 4 |
| `tools.md` | Редактирование метаданных | 34 |
| `tools.form` | Формы | 11 |
| `tools.tests` | Каркас тестов xUnitFor1C | 4 |
| `tools.testrun` | Запуск тестов xUnit | 4 |
| `tools.privacy` | Обезличивание ПДн 152-ФЗ | 4 |
| **Итого** | | **100** |

## Сборка из исходников

Нужна только разработчикам плагина — для обычной установки достаточно [update site](#2-установить-плагин-из-update-site).

Требуется: **1C:EDT 2026.x** (сборка идёт против его API — см. [матрицу](#матрица-поддержки-1cedt)), **JDK 17**, **Maven 3.9+**, **Git**.

```bash
git clone https://github.com/fedukhin-sys/1C_EDT_MCP_PUBLIC.git
cd 1C_EDT_MCP_PUBLIC
mvn clean verify
```

Tycho тянет target platform из локально установленного 1C:EDT (пул p2 в `C:/Users/<user>/.p2/pool/plugins` на Windows — см. `targets/default/default.target`). Готовый p2-сайт окажется в `repositories/ru.fedukhin.edt.mcp.repository/target/repository/` — его можно поставить через **Help → Install New Software → Add → Local…**.

Подробнее об окружении, тестах и релизном процессе — [`CONTRIBUTING.md`](CONTRIBUTING.md). Как публикуется update site (self-hosted runner + GitHub Pages) — [`docs/p2-publishing.md`](docs/p2-publishing.md).

## Лицензия

Apache License 2.0.
