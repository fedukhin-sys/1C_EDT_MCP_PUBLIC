# 1C:EDT MCP Server

MCP-сервер для **1C:Enterprise Development Tools** (1C:EDT) — Eclipse-плагин,
который поднимает внутри IDE HTTP+SSE-сервер по протоколу
[Model Context Protocol](https://modelcontextprotocol.io) и выставляет
**84 инструмента** для управления проектами «1С:Предприятия» извне:
из любого MCP-клиента (Claude Code, Claude Desktop, mcp-inspector и т.п.).

LLM-агент через эти инструменты может:

- читать и менять workspace, открывать/создавать проекты конфигурации и расширений;
- читать, писать и инспектировать модули BSL;
- создавать и редактировать объекты метаданных (Справочники, Документы,
  Регистры, Перечисления, Константы, ОбщиеМодули, Подсистемы, …);
- заимствовать объекты родителя в расширение (включая формы и общие картинки);
- работать с формами (читать структуру, добавлять атрибуты/реквизиты/команды/элементы);
- работать с инфобазами (создавать, ассоциировать, деплоить конфигурацию);
- запускать тонкий/толстый клиент «1С:Предприятия», в т.ч. под отладчиком;
- управлять отладкой BSL (точки останова, стек, пошаговое выполнение, вычисление выражений);
- запускать проверки качества кода EDT и работать с маркерами;
- разворачивать [xUnitFor1C](https://github.com/xUnitFor1C/xUnitFor1C)-каркас
  и автоматически прогонять тесты на развёрнутой инфобазе;
- собирать отчёты на СКД через программное API.

Сервер раздаёт **Bearer-токеном** защищённый эндпоинт SSE,
живёт в процессе IDE и стартует автоматически при её запуске.

> **Статус:** v1.12.0, 84 MCP-инструмента, ~640 unit-тестов, прошёл серию live
> smoke-проверок на реальных конфигурациях. Полный перечень инструментов —
> в [`docs/tools.md`](docs/tools.md).

---

## Quick Start

> Минимум, чтобы попробовать сервер «из коробки».

### 1. Требования

| Компонент            | Версия                                          |
| -------------------- | ----------------------------------------------- |
| 1C:EDT               | **2026.1** (Eclipse Platform 4.30 / 2023-12)    |
| JDK                  | **17**                                          |
| Maven                | **3.9+**                                        |
| Платформа «1С»       | для запуска инфобаз и xUnitFor1C-прогонов       |
| ОС                   | Windows / Linux / macOS (smoke на Windows 10)   |

### 2. Сборка

```powershell
mvn clean verify
```

Tycho тянет target platform из локального p2-пула 1C:EDT
(`C:/Users/User/.p2/pool/plugins`, см. `targets/default/default.target`).
Если у вас EDT установлен по другому пути — поправьте `<location path=...>`
в `default.target` на свой `.p2/pool/plugins`.

Артефакт:

- p2 update site: `repositories/ru.fedukhin.edt.mcp.repository/target/repository/`

### 3. Установка в 1C:EDT

В IDE: **Help → Install New Software → Add → Local…** → указать
`repositories/ru.fedukhin.edt.mcp.repository/target/repository/` → отметить
**EDT MCP** → Next → Finish → перезапустить IDE.

### 4. Настройка

**Window → Preferences → EDT MCP**:

- **Port** — порт SSE (по умолчанию `3001`);
- **Auto-start on IDE launch** — автозапуск при старте IDE (по умолчанию вкл);
- **Bearer token** — read-only поле, кнопка **Regenerate token** ротирует токен.

При смене порта сервер перезапускается автоматически. В строке статуса
снизу отображается состояние: `MCP :3001 ●` (зелёное), `MCP: stopped` (серое),
`MCP: error` (красное). Клик по индикатору открывает страницу настроек.
Меню `Window → EDT MCP` содержит явные команды Start / Stop / Restart.

### 5. Подключение MCP-клиента

#### mcp-inspector (для проверки руками)

```bash
npx @modelcontextprotocol/inspector \
    --transport sse \
    --url http://127.0.0.1:3001/mcp/sse \
    --header "Authorization: Bearer <ваш токен из Preferences>"
```

Ожидаемо:

- `initialize` отдаёт `EDT_MCP 1.0.0`;
- `tools/list` показывает 84 инструмента;
- запрос без валидного `Authorization` → `401 Unauthorized`.

#### Claude Code / Claude Desktop

Пример фрагмента конфига (`~/.claude.json` или Claude Desktop `claude_desktop_config.json`)
для SSE-транспорта:

```json
{
  "mcpServers": {
    "edt-mcp": {
      "transport": "sse",
      "url": "http://127.0.0.1:3001/mcp/sse",
      "headers": {
        "Authorization": "Bearer YOUR_TOKEN_HERE"
      }
    }
  }
}
```

После перезапуска клиента инструменты `list_projects`, `create_md_object`,
`deploy_project` и т.д. должны стать доступны агенту.

### 6. Smoke-сценарий «всё работает»

1. Открыть в EDT любой проект-расширение (или конфигурацию).
2. Из MCP-клиента вызвать `list_projects` — должен вернуть открытые проекты.
3. Вызвать `check_list_markers project=<имя>` — получить текущие маркеры EDT.
4. Создать тестовый объект: `create_md_object kind=CommonModule name=ТестМодуль project=<имя>` →
   убедиться, что в дереве конфигурации появился `ОбщиеМодули/ТестМодуль`.

---

## Архитектура

Девять Tycho-бандлов + feature + p2-репозиторий + target platform:

| Бандл                                              | Назначение                                            |
| -------------------------------------------------- | ----------------------------------------------------- |
| `ru.fedukhin.edt.mcp.core`                         | Bearer-фильтр, embedded Jetty 12 (EE10), MCP SDK SSE-servlet, реестр инструментов, lifecycle сервера, Guice-обвязка (`com._1c.g5.wiring`). |
| `ru.fedukhin.edt.mcp.tools.edt`                    | Workspace/project (Stage 0–1), `IV8ProjectManager`, `IRuntimeVersionSupport`. |
| `ru.fedukhin.edt.mcp.tools.bsl`                    | BSL-модули (Stage 2), regex-парсер (Xtext-маршрут задокументирован, но не используется). |
| `ru.fedukhin.edt.mcp.tools.infobase`               | Инфобазы и deploy (Stage 3), `IInfobaseManager`/`AssociationManager`/`SynchronizationManager`. |
| `ru.fedukhin.edt.mcp.tools.client`                 | Запуск 1С-клиента (Stage 3b), `IResolvableRuntimeInstallationManager`, in-memory `ClientProcessRegistry`. |
| `ru.fedukhin.edt.mcp.tools.debug`                  | Отладка BSL (Stage 3c), `IBslBreakpointFactory`, `IBreakpointManager`, `DebugSessionRegistry`. |
| `ru.fedukhin.edt.mcp.tools.quality`                | Проверки EDT (Stage 4), check engine + маркеры. |
| `ru.fedukhin.edt.mcp.tools.md`                     | Редактирование метаданных (Stage 5+8), BM Framework (`IBmModelManager`, `IBmTransaction`), парсер short-string-типов, MdObject-регистр. |
| `ru.fedukhin.edt.mcp.tools.form`                   | Инспекция и редактирование форм (Stage 6+8a–8e), рефлексивный доступ к `com._1c.g5.v8.dt.form.model`. |
| `ru.fedukhin.edt.mcp.tools.tests`                  | xUnitFor1C-скаффолдинг (Stage 7), создание тест-модулей через regex-манипуляции BSL. |
| `ru.fedukhin.edt.mcp.tools.testrun`                | Прогон xUnitFor1C-тестов (Stage 7b) — установка раннера, запуск, парсинг отчёта. |
| `ru.fedukhin.edt.mcp.ui`                           | Preferences-страница, команды Start/Stop/Restart, status bar. |

Регистрация инструментов идёт через extension point
`ru.fedukhin.edt.mcp.core.tool` — см. `bundles/ru.fedukhin.edt.mcp.tools.edt/plugin.xml`.

---

## Список инструментов

Полный актуальный список 84 MCP-инструментов с JSON-Schema аргументов и
краткими описаниями — в [`docs/tools.md`](docs/tools.md).

Группы:

- **Workspace & projects** — `list_projects`, `get_project`, `open_project`, `close_project`, `create_project`, `list_project_files`, `get_workspace_info`, `list_runtime_versions`.
- **BSL — модули** — `read_module`, `write_module`, `get_module_info`, `list_module_methods`, `get_method`.
- **Инфобазы & deploy** — `list_infobases`, `get_infobase`, `create_infobase`, `associate_infobase`, `deploy_project`.
- **Запуск клиента** — `run_client`, `list_running_clients`, `stop_client`.
- **Отладка BSL** — `set_breakpoint`, `list_breakpoints`, `remove_breakpoint`, `list_debug_sessions`, `get_debug_state`, `get_stack`, `debug_resume`, `debug_pause`, `debug_step`, `debug_client`, `stop_debug`, `get_variables`, `evaluate`.
- **Качество кода** — `check_catalog`, `check_describe`, `check_run`, `check_list_markers`.
- **Метаданные** — `list_md_objects`, `get_md_object`, `create_md_object`, `rename_md_object`, `set_md_property`, `set_md_type`, `list_attributes`, `add_attribute`, `rename_attribute`, `add_tabular_section`, `add_tabular_section_attribute`, `add_register_recorder`, `add_subsystem_content`, `set_constant_type`, `borrow_md_object`, `add_extension_method_override`.
- **Формы** — `list_forms`, `get_form`, `get_form_item`, `create_form`, `add_form_attribute`, `add_form_command`, `add_form_field`, `add_form_group`, `add_form_table`, `add_form_table_column`, `add_form_button`, `borrow_form`, `borrow_form_pictures`.
- **xUnitFor1C scaffolding** — `list_test_modules`, `get_test_methods`, `create_test_module`, `add_test_method`.
- **xUnitFor1C прогон** — `install_test_runner`, `uninstall_test_runner`, `run_tests`, `run_test_method`.
- **СКД (Система компоновки данных)** — `create_data_composition_schema`, `add_dcs_data_set_query`, `add_dcs_data_set_object`, `add_dcs_data_set_link`, `add_dcs_field`, `add_dcs_calculated_field`, `add_dcs_total_field`, `add_dcs_parameter`, `set_dcs_query_text`.

---

## Claude Code skills

В `.claude/skills/` лежат два Claude Code-скилла-компаньона:

- **[`edt-mcp`](.claude/skills/edt-mcp/SKILL.md)** — карта 84 MCP-инструментов: какие
  баги известны, какие паттерны вызовов проверены на реальных конфигурациях,
  как правильно последовательно создавать MdObject + атрибуты + формы.
- **[`edt-skd`](.claude/skills/edt-skd/SKILL.md)** — доменный справочник по СКД
  (Система компоновки данных): структура `.dcs`, наборы данных, роли полей,
  параметры/связи/настройки, реальный формат файла. Содержимое выверено по
  Хрусталёвой и по корпусу 288 схем ЕСС.

Если открыть staging-каталог в Claude Code, оба скилла автоматически
доступны как `/edt-mcp` и `/edt-skd`.

## Реализационные заметки

- **MCP SDK:** `io.modelcontextprotocol.sdk:mcp-core:1.1.2` (+ `mcp-json-jackson2`).
  `McpJsonMapper` и `JsonSchemaValidator` пробрасываются явно — ServiceLoader/OSGi DS
  внутри tycho-surefire работает ненадёжно.
- **Target platform** собирается из локально установленного p2-пула 1C:EDT 2026.1
  через `<location type="Directory">`. Онлайновые p2 IU-режимы не разрешают
  EDT + Eclipse 2023-12 + Xtext чисто.
- **HTTP-транспорт** — embedded Jetty 12 EE10 в виде bundled jar внутри `core`
  (1C:EDT 2026.1 везёт несовместимый Servlet 3.1 / Jakarta 4.0). Реальные URL:
  SSE `http://<host>:<port>/mcp/sse`, messages `http://<host>:<port>/mcp/messages?sessionId=…`.
- **Тесты** в pom-родителе включают `mcp.security.useInMemory=true` для
  in-memory-хранения токенов (продакшен использует Equinox secure preferences).
- **BM Framework** — все mutating-операции с метаданными идут через
  `IBmTransaction`, после write выполняется FS-polling с retry (50/100/200/400/800/1600мс),
  потому что `BmPersistentExecutor` пишет `.mdo` асинхронно.

---

## Известные ограничения

- `rename_md_object` / `rename_attribute` — α: каскадного переименования по
  BSL и `TypeDescription` нет, сломанные ссылки — целенаправленный сигнал.
- `write_module` пока валидирует только баланс `Процедура`/`КонецПроцедуры`.
  Полная Xtext-валидация не интегрирована (см. Stage 2 spec).
- `debug_client` отдаёт `pid` отдельной сессии и **не появляется** в
  `list_running_clients` (in-memory `@Singleton` живёт в другом бандле).
- `run_tests` / `run_test_method` — best-effort timeout: при таймауте
  future отменяется, но 1С может ещё дописать stdout.

Полный список багов и их статус закрытия — в журнале коммитов и
`docs/tools.md` («Pass 1 — known bugs»).

---

## Лицензия

Внутренний проект. Условия использования согласовывайте с автором —
[fedukhin-sys](https://github.com/fedukhin-sys).

## Контакты

GitHub: [@fedukhin-sys](https://github.com/fedukhin-sys)
