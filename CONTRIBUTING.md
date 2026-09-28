# Contributing — EDT_MCP

## Требования к окружению

- JDK 17.
- Maven 3.9+ (бинарь `mvn` не обязан быть в PATH; пример пути: `E:\Tools\maven\apache-maven-3.9.9\bin\mvn.cmd`).
- Локально установленный 1C:EDT 2026.x; target platform берёт бандлы **одной** его установки — см. `targets/default/default.target` и раздел ниже. Матрица поддерживаемых веток EDT в runtime — в [`README.md`](README.md#матрица-поддержки-1cedt).

### Пул бандлов для target platform

Общий p2-пул (`~/.p2/pool/plugins`) напрямую не годится, как только на машине стоят две
версии EDT (например, 2026.1 и 2026.2): в пуле тогда два набора бандлов с разными версиями
библиотек, компиляция проходит, а тестовый OSGi-рантайм tycho-surefire не стартует
(uses constraint violation на `com.google.common.base`, Guava 32 против 33). Поэтому
Directory-локация target-файла указывает на каталог жёстких ссылок ровно на бандлы из
`bundles.info` нужной установки. Каталог строится один раз и после каждого обновления EDT:

```powershell
./scripts/make-edt-target-pool.ps1 `
    -Installation "$env:LOCALAPPDATA/1C/1cedtstart/installations/1C_EDT 2026.1 (1)/1cedt" `
    -OutDir "$env:USERPROFILE/.p2/edt-target-2026.1"
./scripts/set-edt-pool.ps1 -TargetFile targets/default/default.target `
    -PoolPath "$env:USERPROFILE/.p2/edt-target-2026.1/plugins"
```

Жёсткие ссылки места не занимают, но должны лежать на одном томе с пулом; прав
администратора не нужно. Версию установки выбирайте ту, в которой реально работаете, —
плагин компилируется и тестируется против неё.

## Сборка

```
mvn clean verify
```

Артефакты:
- p2 update site: `repositories/ru.fedukhin.edt.mcp.repository/target/repository/`.
- Тестов: **1369, 0 failures, 10 skipped** (замер на v1.24.0, 2026-09-27). Unit + integration с полным MCP SSE handshake; 10 `@Ignore` — Jackson LinkageError под tycho-surefire и headless-xtext ограничения, см. javadoc на самих классах. Число растёт с каждым PR — источник истины всегда вывод `mvn verify`, а не эта строка.

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
- Target platform — `<location type="Directory">` на пул одной локальной установки 1C:EDT 2026.x (см. «Пул бандлов для target platform»; онлайн p2 InstallableUnit-режим не разруливает EDT + Eclipse 2023-12 + Xtext set).
- Длинные операции — фоновые задания `ru.fedukhin.edt.mcp.core.jobs` (`McpJobs` — синглтон процесса, как `PrivacyState`): инструмент берёт межпроцессный замок базы, запускает Eclipse Job и сразу отвечает `jobId`; замок снимает само задание. Итог — `get_job_status`. Тесты заданий используют `JobLauncher.synchronous()`.
- API синхронизации v2 (`IInfobaseSynchronizationStateManager` и т.п.) нельзя биндить в Guice: на EDT без него упадёт инжектор всего бандла. Классы, которые биндятся, обязаны загружаться и линковаться и без API v2, а тип грузится в двух случаях: (1) Guice при создании инжектора читает рефлексией сигнатуры конструкторов, методов и полей — v2-типов в них быть не должно; (2) верификатор при линковке класса проверяет присваиваемость — значение передаётся, возвращается или сохраняется туда, где ожидается тип с **другим** именем (так `HeadlessInfobaseChangesResolver`, переданный в параметр `IInfobaseChangesResolver`, заставил бы загрузить интерфейс). `checkcast`, вызовы методов (`invoke*`) и class-литералы (`ldc`) резолвят тип лениво, при первом исполнении. Образец ленивого доступа — `SyncV2`; вызов `retrieveInfobaseChanges` с обработчиком живёт в небиндящемся `HeadlessInfobaseChangesResolver`. Проверка — `ProjectFromInfobaseUpdaterLinkageTest`: прячет от загрузчика `sync.v2.*`/`IInfobaseChangesResolver`, заново определяет классы бандла и линкует каждый класс из `ToolsInfobaseModule` (список — из class-литералов модуля). Внутренний (не API) метод EDT — только рефлексией, без статических ссылок, а сбой — причина в предупреждении, не исключение: образец — `SyncV2.markSynchronized` (`getDelegate()` → `forceEdtSynchronization`, отметка проекта синхронизированным после обновления из базы). Классы значений, которых может не быть на старой ветке (`EdtResourceMetadata` — значения default-методов `IResourceStoreManager`), в биндящихся классах не называть вовсе: снимки подписей держатся как `Map<String, ?>` и сравниваются `Objects.equals`. Службу, чья регистрация на старой ветке не проверена (`IResourceStoreManager`), в Guice не биндить и в `@Inject`-конструктор не брать — иначе упадёт создание класса, и инструменты молча пропадут из tools/list вместо понятного отказа: брать лениво (`ServiceAccess` в момент вызова, держать как `Object`, шов `Supplier<Object>` для тестов — как `ProjectFromInfobaseUpdater`). Тест линковки прячет весь пакет `dt.core.resource` и сверяет параметры `@Inject`-конструктора `ProjectFromInfobaseUpdater` и привязки модуля.
- Внутренние (не API) члены EDT, на которые опирается бандл `tools.infobase`, — только рефлексией (без статических ссылок и без упоминания в сигнатурах и полях биндящихся классов); сбой — «не сделано» с причиной по-русски, не исключение. Все — в пакете `com._1c.g5.v8.dt.internal.platform.services.core.infobases.sync.v2` (`dt.platform.services.core`), сверены `javap -p` в 2026.1 (core 23) и 2026.2 (core 24) — одинаковы:
  - `InfobaseSynchronizationStateManager.getDelegate()` → `InfobaseSynchronizationStateManagerDelegate.forceEdtSynchronization(InfobaseReference, IProject)` (оба публичные) — `SyncV2.markSynchronized`, отметка проекта совпадающим с базой после обновления из базы (L8). Фолбэк — проект не отмечен, предупреждение с причиной;
  - у того же делегата `private` `calculateStoreProject`, `initLockStatesIfAbsent`, `findOrCreateProjectInfobaseSynchronizationStateHolder`, `lockInfobaseState`, `unlockInfobaseState` и поле `lock`; у держателя `…Delegate$ProjectInfobaseSynchronizationStateHolder` (пакетный класс) поля `state` и `synchronizationStore`; `InfobaseSyncState` — публичный конструктор `(long, String, Map, Map, String)`, геттеры, статический `UNDEFINED`; `InfobaseSynchronizationStateStore.writeEdtSyncrhonizationState`, `getMainPlatformResourceVersionsPath`, `getExtensionPlatformResourceVersionsPath`; природа `com._1c.g5.v8.dt.core.V8ExtensionNature` — `SyncV2.forceRecheck` → `EdtSyncStateRecheck` (в Guice не биндится): перед каждым обновлением проекта из базы — пустой идентификатор поколения данных в записанном состоянии, при полной замене ещё и перенос `ConfigDumpInfo` пары в сторону (`<имя>.mcp-prev`; F4: иначе EDT отвечает «изменений нет», не сравнивая конфигурацию). После забора `SyncV2.settleDumpInfo` → `EdtSyncStateRecheck.restore` под теми же замками: полной перезагрузки не было — копия возвращается на место (EDT записала свой — остаётся её), была — копия удаляется (fix round 9). Порядок — шаг в шаг как `forceEdtSynchronization`. Фолбэк — обновление идёт как раньше, при `NO_CHANGES` — предупреждение с причиной; копию не вернуть — предупреждение «ни разу не синхронизированным… перезапустите EDT».
  Новая ветка EDT — сверить `javap -p` этих классов; юнит-тесты — подделки с теми же именами членов (`SyncV2Fakes`), настоящую EDT они не заменяют: после смены ветки нужен живой прогон сценария 3.
  - Не рефлексия, но то же внутреннее поведение EDT — «шлюз» соединения с базой (fix round 7): `DesignerSessionInfobaseConnection.externalChangesCheckRequired` после каждого забора `false`, и EDT отвечает `NO_CHANGES`, не спрашивая базу; `true` его делает только закрытие сеанса агента конфигуратора (слушатель `closed()`). Поэтому `ProjectFromInfobaseUpdater` перед каждым забором вызывает `ThickClientOps.releaseDesignerSession` — публичный `IDesignerSessionThickClientLauncher.closeDesignerSession` под замком базы. Фолбэк — сбой закрытия: `ConfigDumpInfo` не трогается, при `NO_CHANGES` — предупреждение с причиной. Закрытие без ошибки открытого шлюза не доказывает (флаг снаружи не виден), а сбой самой проверки `isFlowActive` — fail-closed: сеанс не закрывается (fix round 9). Новая ветка EDT — проверить в трассировке (`/trace/infobase`), что у забора после обновления `requiresSynchronization=true`.
  - Фоновая проверка EDT после запуска или открытия проекта (fix round 8, L9) — задание `com._1c.g5.v8.dt.internal.platform.services.core.infobases.sync.InfobaseSynchronizationManager$ProjectSynchronizationStateUpdateScheduler$SynchronizationStateUpdateJob` (в 2026.1 и 2026.2 — один класс, наследник `Job`, без правила планирования). `EdtSyncStateJobs` узнаёт его ТОЛЬКО по имени класса строкой (`Job.getJobManager().find(null)`, затем `Job.join` с пределом) — первым шагом каждого задания и в начале обновления проекта; `SyncV2.awaitNoActiveFlow` — спящий опрос `isFlowActive` перед закрытием сеанса агента, сбросом и отметкой. Фолбэк: класс переименован в новой ветке — ждать просто нечего (защита от столкновения пропадёт, но ничего не сломается); проверить `unzip -l` бандла `dt.platform.services.core` при смене ветки.
- Пакетный `1cv8` из Java (`DesignerBatch`, `RuntimeCli`): ключ и значение — **отдельными элементами** команды (`"/N", "Имя Фамилия"`, `"/F", "<каталог>"`), как их собирает сама EDT. `ProcessBuilder` JDK 17 (legacy-режим) берёт элемент с пробелом в кавычки целиком, и слитный `/N"Имя Фамилия"` 1cv8 не понимает. `importCfToInfobase` исполнителя EDT не передаёт `-Extension` (грузит `.cfe` как основную конфигурацию) — расширения загружает `ThickClientOps.loadExtension` через `DesignerBatch`, закрыв сеанс агента EDT на базе и добавив то же, что исполнитель EDT добавляет к каждой операции: «Дополнительные параметры» базы (`/UC…`, `/Z…`) и `/WA -`/`/WA +` с учётными данными.
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
