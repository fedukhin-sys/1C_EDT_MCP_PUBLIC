# Contributing — EDT_MCP

## Требования к окружению

- JDK 17.
- Maven 3.9+ (бинарь `mvn` не обязан быть в PATH; пример пути: `E:\Tools\maven\apache-maven-3.9.9\bin\mvn.cmd`).
- Локально установленный 1C:EDT 2026.x; target platform тащится из его p2-пула (`C:/Users/User/.p2/pool/plugins`) — см. `targets/default/default.target`. Матрица поддерживаемых веток EDT в runtime — в [`README.md`](README.md#матрица-поддержки-1cedt).

## Сборка

```
mvn clean verify
```

Артефакты:
- p2 update site: `repositories/ru.fedukhin.edt.mcp.repository/target/repository/`.
- Тестов: **1019, 0 failures, 10 skipped** (замер на v1.23.0, 2026-08-18). Unit + integration с полным MCP SSE handshake; 10 `@Ignore` — Jackson LinkageError под tycho-surefire и headless-xtext ограничения, см. javadoc на самих классах. Число растёт с каждым PR — источник истины всегда вывод `mvn verify`, а не эта строка.

## Структура

14 bundles (12 tool-бандлов + `core` + `ui`) + feature + p2 repo + target platform:

- `bundles/ru.fedukhin.edt.mcp.core` — Bearer auth, embedded Jetty 12 (EE10), MCP SDK SSE servlet, tool registry, lifecycle, Guice wiring (`com._1c.g5.wiring`).
- `bundles/ru.fedukhin.edt.mcp.tools.edt` — workspace/project tools, `IV8ProjectManager` + `IRuntimeVersionSupport`.
- `bundles/ru.fedukhin.edt.mcp.tools.bsl` — BSL-модули, regex-парсер (Xtext-путь не используется, см. Stage 2 spec).
- `bundles/ru.fedukhin.edt.mcp.tools.infobase` — `IInfobaseManager`, `IInfobaseAssociationManager`, `IInfobaseSynchronizationManager`, `IResolvableRuntimeInstallationManager` + `IRuntimeComponentManager`.
- `bundles/ru.fedukhin.edt.mcp.tools.eventlog` — парсер `.lgf`/`.lgp` (legacy text v2.0), `EventLogLocator` для FILE/SERVER ИБ.
- `bundles/ru.fedukhin.edt.mcp.tools.client` — `IResolvableRuntimeInstallationManager` + in-memory `@Singleton ClientProcessRegistry`.
- `bundles/ru.fedukhin.edt.mcp.tools.debug` — `IBslBreakpointFactory`, `IBreakpointManager`, `DebugPlugin` listener, `@Singleton DebugSessionRegistry`, `DebugStateReader`.
- `bundles/ru.fedukhin.edt.mcp.tools.md` — BM Framework (`IBmModelManager`, `IBmTransaction`) + DOM-route для `.mdo` + `.dcs` editor.
- `bundles/ru.fedukhin.edt.mcp.tools.form` — read + write `Form.form` (XDTO) для CommonForm и nested-form объектов.
- `bundles/ru.fedukhin.edt.mcp.tools.quality` — validator/marker API.
- `bundles/ru.fedukhin.edt.mcp.tools.tests` — xUnitFor1C-каркас (создание CommonModule + методов).
- `bundles/ru.fedukhin.edt.mcp.tools.testrun` — auto-run xUnitFor1C под живой ИБ (`ManagedApplicationModule` handler + `1cv8.exe ENTERPRISE`).
- `bundles/ru.fedukhin.edt.mcp.tools.privacy` — управляющий контур обезличивания ПДн 152-ФЗ (каталог ПДн, per-infobase флаг, журнал). Сам редактор — `ru.fedukhin.edt.mcp.core.privacy`.
- `bundles/ru.fedukhin.edt.mcp.ui` — `AbstractUIPlugin`, preference page, команды, status-bar.

Tools регистрируются через extension point `ru.fedukhin.edt.mcp.core.tool` — пример в `bundles/ru.fedukhin.edt.mcp.tools.edt/plugin.xml`.

## Implementation notes

- MCP SDK: `io.modelcontextprotocol.sdk:mcp-core:1.1.2` (+ `mcp-json-jackson2`).
  `McpJsonMapper` и `JsonSchemaValidator` передаются явно — SDK ServiceLoader / OSGi DS-путь нестабилен под tycho-surefire.
- Target platform — `<location type="Directory">` на локальный 1C:EDT 2026.x pool (онлайн p2 InstallableUnit-режим не разруливает EDT + Eclipse 2023-12 + Xtext set).
- Расходящееся между ветками EDT платформенное API (`IInfobaseSynchronizationManager`: `resolveInfobaseChanges`, `isConnected`, `updateInfobase`) вызывается только через рефлексию с фолбэком — компилируемся против core 23, но обязаны работать и на core 18/19. Прямой вызов такого метода = `NoSuchMethodError` на 2023.x. Тесты фолбэка — подменой `protected`-обёртки в подклассе (отсутствие метода в юнит-тесте не сымитировать).
- Тесты используют in-memory token storage (`mcp.security.useInMemory=true`, см. parent pom tycho-surefire `<systemProperties>`); production — Equinox secure preferences.

## Релизный процесс

1. Все доки/код приведены в соответствие.
2. `mvn verify` → BUILD SUCCESS, 0 failures (skipped-тесты — только заведомые `@Ignore`, см. «Сборка»).
3. PR в `main` с описанием изменений.
4. После merge — `git tag vX.Y.Z` + `git push origin vX.Y.Z`.
5. `gh release create vX.Y.Z` (опционально с release notes).
6. Sync публичного зеркала `1C_EDT_MCP_PUBLIC` (snapshot-коммит).

## Bumping версии

Версия живёт в четырёх местах, все должны быть согласованы (иначе
`tycho-packaging-plugin:validate-version` падает):
- `features/ru.fedukhin.edt.mcp.feature/feature.xml` → `version="X.Y.Z.qualifier"`.
- `features/ru.fedukhin.edt.mcp.feature/pom.xml` → `<version>X.Y.Z-SNAPSHOT</version>`.
- `bundles/ru.fedukhin.edt.mcp.core/META-INF/MANIFEST.MF` → `Bundle-Version: X.Y.Z.qualifier`.
- `bundles/ru.fedukhin.edt.mcp.core/pom.xml` → `<version>X.Y.Z-SNAPSHOT</version>`
  (собственная версия бандла, НЕ версия `<parent>` — её не трогать).

Ядро версионируется вместе с релизом намеренно: `McpServerLifecycle.bundleVersion()`
берёт `Bundle-Version` именно этого бандла и отдаёт клиенту в `serverInfo` ответа
`initialize`, а также пишет в маячок инстанции. Пока ядро стояло на 0.1.0, клиент
видел «0.1.0» на релизе 1.22.1.

Bundle-Version остальных bundle'ов — отдельный жизненный цикл, обычно не меняется.

## Известные ограничения

- 10 тестов `@Ignore` (= `Skipped: 10` в выводе `mvn verify`), двумя группами:
  - 6 × `McpServer*IntegrationTest` + `BslAstReaderIntegrationTest` — Jackson LinkageError под tycho-surefire (Jackson 2.20 внутри `core` bundle ↔ EDT runtime classloader) и ограничения headless-xtext;
  - `Stage3cDebugLaunchProbeTest`, `InfobaseRegistryIntegrationTest`, `ClientLauncherIntegrationTest` — требуют живой IDE / 1С runtime, manual smoke only.
- Схемы с `anyOf`/`oneOf` (`query_event_log`, `get_event_log_path`) MCP SDK клиенту **не публикует** — record `JsonSchema` не имеет таких полей. Взаимоисключающие аргументы приходится дублировать словами в `description` инструмента.
- `add_use_as_is_reference` отвергнут как broken (revert `b9811b1`); use-as-is CommonForm ⇒ обязательный `borrow_md_object` flow (full inline-borrow).
- `extend_form_attribute_type` отменён 2026-05-19 (нет канонического образца для reverse-engineering, deploy=зелёный без него).

## Несколько инстанций 1C:EDT на одной машине

Плагин рассчитан на параллельную работу нескольких запущенных 1C:EDT.

- **Порт** — не одно значение, а диапазон (`port` … `portRangeEnd`, по умолчанию
  3001–3006). Инстанция при старте занимает первый свободный. Подобранный порт
  **нельзя** записывать обратно в настройки: на ключи `port` и `portRangeEnd` висит
  `IPreferenceChangeListener`, который перезапускает сервер, — получится каскад.
- **`~/.edt-mcp/`** — каталог межпроцессного состояния, общий для всех инстанций
  одного пользователя: `instances/` (маячки), `locks/` (замки), `privacy/`
  (флаги ПДн и журналы обезличивания). Путь переопределяется системным свойством
  `mcp.discovery.dir`; оно проставлено в `tycho-surefire`, иначе тесты писали бы
  в реальный домашний каталог.
- **Замки** — `ru.fedukhin.edt.mcp.core.ipc.InterProcessLock`, поверх
  `FileChannel.tryLock`. Блокируется **только байт 0**, метаданные держателя
  лежат со смещения 1: на Windows блокировка мандатная и залоченный диапазон не
  читается из другого процесса, а текст держателя нужен именно чужому процессу.
  Внутрипроцессный слой — `Semaphore`, а не `ReentrantLock`: последний
  реентрантен и пропустил бы повторный захват в `OverlappingFileLockException`.
- **Ключ замка для операций с базой** — информационная база, а не проект и не
  рабочая область: один проект деплоится в разные базы, разные расширения — в
  одну. Проектная сторона и так эксклюзивна, Eclipse держит OS-lock на
  `.metadata/.lock`.
- **`TypeReference` под OSGi не использовать.** Анонимный подкласс даёт
  `loader constraint violation`: вендоренный в `core` Jackson и Jackson соседнего
  бандла грузятся разными загрузчиками. Читать через `Class` и приводить руками.
