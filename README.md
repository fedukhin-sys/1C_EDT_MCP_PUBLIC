# EDT_MCP

MCP server plugin for 1C:EDT — **v1.15.0** (workspace, projects, BSL modules, infobases, deploy, client launch, debug, quality, metadata edit, form authoring, xUnitFor1C scaffolding and execution, DCS schema editing, event-log queries).

Exposes a Bearer-protected HTTP+SSE MCP server inside 1C:EDT with **89 MCP tools** для управления workspace, проектами, модулями BSL, информационными базами (включая deploy), запуском клиента 1С, сессиями отладки, проверками качества, метаданными (CRUD + editor для 11 видов объектов и табличных частей), формами (создание + UI-элементы), схемами компоновки данных (`.dcs`), журналом регистрации и xUnitFor1C (создание модулей + auto-run).

UI: Preferences page, status bar item, Start/Stop/Restart commands under **Window → EDT MCP**.

## Install

В IDE: **Help → Install New Software → Add → Local…**, указать на собранный p2-репозиторий
`repositories/ru.fedukhin.edt.mcp.repository/target/repository/`, отметить **EDT MCP**, Next, Finish,
рестарт IDE.

## Configure

**Window → Preferences → EDT MCP**:
- **Port** (default 3001).
- **Auto-start on IDE launch** (default off).
- **Bearer token** — read-only; **Regenerate token** меняет.

Смена порта рестартит сервер автоматически.

Status bar:
- `MCP: stopped` / `MCP: starting` — серый
- `MCP :<port> ●` — зелёный
- `MCP: error` — красный, tooltip с сообщением

Клик по статус-итему открывает Preferences. `Window → EDT MCP` — явные Start / Stop / Restart.

## Sanity-check через mcp-inspector

```
npx @modelcontextprotocol/inspector --transport sse \
    --url http://127.0.0.1:3001/mcp/sse \
    --header "Authorization: Bearer <token from Preferences>"
```

Ожидаемо:
- `initialize` успешен.
- `tools/list` показывает все 89 tools.
- Запросы без валидного `Authorization` → 401.

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
