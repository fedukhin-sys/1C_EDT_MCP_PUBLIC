# История изменений

Все значимые изменения публичной версии EDT_MCP. Формат — [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версии — по [семантическому](https://semver.org/lang/ru/) принципу.

## [1.22.0] — 2026-08-14

Импорт готовых `.epf`/`.erf` в проект внешних объектов. До сих пор файл внешней
обработки заводился в 1C:EDT только руками — мастером «Импорт → Внешние отчёты и
обработки» в IDE, поэтому сценарий «взять существующую обработку и дальше править её
исходники в EDT» не автоматизировался.

### Добавлено

- **`import_external_object`** — импорт `.epf`/`.erf` в существующий проект внешних
  объектов тем же путём, что мастер IDE: EDT распаковывает файл в XML конфигуратора
  (`IExternalObjectRestorer`) и импортирует его в исходники EDT
  (`IImportOperationFactory`). Аргументы: `project`, `file`, `overwrite`,
  `timeoutSeconds`.
  - версия платформы и базовый проект конфигурации берутся из самого проекта
    (`DT-INF/PROJECT.PMF`) — в аргументах их нет;
  - информационная база для распаковки — та, что связана с проектом
    (`associate_infobase`); на время импорта она блокируется, как и при сборке;
  - **имя объекта определяется содержимым файла, а не его именем**: `АРМ_150626.epf`
    даёт `ExternalDataProcessor.АРМ`. Переименовать импортированный объект можно
    через `rename_md_object`;
  - существующий объект с тем же именем не перезаписывается, пока не передан
    `overwrite=true` (в IDE на этом месте диалог с вопросом).

Итого инструментов — **100**.

### Исправлено

- Описание `build_external_object` в `plugin.xml` осталось от реализации до v1.18.1
  и обещало несуществующие аргументы `serviceInfobase` / `platformVersion` — приведено
  к фактическому контракту (`project`, `fqn`, `outPath`, `timeoutSeconds`).
- `docs/tools.md`: счётчик инструментов `tools.client` (3 → 5) и итог по таблице
  (97 → 100) разошлись с фактическим составом ещё в 1.21.0.

## [1.21.0] — 2026-08-02

Запуск клиента 1С через launch-конфигурации EDT — тем же кодом, что кнопка
запуска/отладки в IDE. Мотивация: ручная сборка командной строки `1cv8.exe`
(включая `run_client`) может расходиться с EDT — учётные данные, штатно работающие
из IDE, при ручном headless-запуске давали «Пользователь ИБ не идентифицирован».

### Добавлено

- **`list_launch_configurations`** — список launch-конфигураций типа «Клиент
  1С:Предприятия» (`RuntimeClient`): имя, проект, ИБ (резолв uuid приложения в имя),
  тип клиента (thin/thick/web/auto), пользователь, версия платформы. Пароль не
  возвращается никогда — только признак `hasPassword`.
- **`run_launch_configuration`** — запуск конфигурации по имени в режиме `run` или
  `debug` через `ILaunchConfiguration.launch()` → `RuntimeClientLaunchDelegate`:
  учётные данные, тип клиента, версия платформы и обновление ИБ перед стартом —
  ровно те же, что при запуске из IDE. Probe мгновенной смерти процесса (отказ 1cv8
  виден только по exit-коду), таймаут не прерывает запуск (EDT может долго обновлять
  ИБ) — возвращается `completed=false`.

### Технические заметки

- Ключи атрибутов `.launch`-файлов — строковые литералы (формат хранения стабильнее
  compile-зависимости от `com._1c.g5.v8.dt.launching.core` при dual-version
  2023.x/2026.x); новая compile-зависимость только на `org.eclipse.debug.core`.
- `targets/default/default.target` указывает на p2-пул профиля текущей машины;
  CI-раннер с другим профилем переопределяет путь repo-переменной `EDT_POOL_PATH`.

## [1.20.1] — 2026-07-18

Правки по итогам live-smoke 1.20.0: создание «второй волны» kind'ов подтверждено
(ChartOfCharacteristicTypes/Task/ChartOfAccounts/ChartOfCalculationTypes — чистые),
три kind'а и seed конфигурации доведены до валидного состояния.

### Исправлено

- **`create_md_object kind=ExchangePlan` — план обмена без `thisNode`.** Валидатор давал error «Должна быть задана сущность 'thisNode'»: Designer создаёт uuid предопределённого узла «ЭтотУзел» сам, EMF-фабрика — нет. Теперь фабрика проставляет `thisNode` при создании.
- **Seed `create_project type=configuration` — 4 error'а валидатора на свежем проекте.** Configuration и Language создавались без `uuid`, без `dataLockControlMode=Managed` и с дефолтным режимом совместимости метамодели 8.5.1. Seed дополнен; `compatibilityMode` ставится по runtime-версии проекта.

### Добавлено

- **`add_register_recorder` принимает `DocumentJournal` в аргументе `register`** — регистрирует документ в журнале (`<registeredDocuments>`, односторонняя связь).
- **`set_md_property property=task` для BusinessProcess** — значение `Task.X` (FQN), резолвится в Task-объект.

### Проверено live (1.20.0)

Авто-ретрай «already connected» (первая сборка `.epf` свежего сеанса — без ошибки); `anyOf`/`oneOf` доезжают до клиента; `run_tests` без раннера — мгновенный отказ; все 7 новых kind'ов создаются, `CommonForm` корректно отбивается.

## [1.20.0] — 2026-07-17

Устранение исправимых пунктов из списка «Что НЕ работает» скилла: два ограничения
сняты, одно поведение сделано fail-fast.

### Добавлено

- **`create_md_object` создаёт «вторую волну» kind'ов:** `Task`, `BusinessProcess`, `ChartOfAccounts`, `ChartOfCalculationTypes`, `ChartOfCharacteristicTypes`, `ExchangePlan`, `DocumentJournal` переведены в creatable — EMF-фабрика и BM-сериализация дают валидный минимальный `.mdo`, сопутствующие артефакты этим kind'ам не нужны. Не-creatable остались только `CommonForm` (нужен `Form.form`) и `CommonPicture` (нужен файл картинки) — для них по-прежнему `borrow_md_object`.
- **`anyOf`/`oneOf` публикуются в схемах инструментов.** Record `McpSchema.JsonSchema` MCP SDK не несёт этих ключей, и «обязателен один из name/uuid/logDir» у `query_event_log`/`get_event_log_path` был виден клиенту только словами в description. Теперь недостающие ключи Map-схемы дописываются при сериализации tools/list (`JsonSchemaExtras`: реестр extras + кастомный Jackson-сериализатор в нашем ObjectMapper).

### Исправлено

- **`run_tests`/`run_test_method` без установленного раннера падают сразу с подсказкой.** Раньше 1cv8 ENTERPRISE стартовал, селектор никто не читал, и клиент висел до таймаута (минуты); теперь наличие модулей раннера в проекте проверяется до запуска, ошибка называет `install_test_runner` и `deploy_project`.

### Документация

- Скилл `edt-mcp` актуализирован: `build_external_object` описан по новой схеме (без `serviceInfobase`/`platformVersion`, сборка штатным экспорт-сервисом EDT, нужна ассоциация проекта с ИБ), у `run_tests`/`run_test_method` задокументированы `user`/`password`, раздел «Что НЕ работает» пересобран — оставшиеся пункты помечены как ограничения платформы 1С/EDT.

## [1.19.2] — 2026-07-17

Правка по итогам live-smoke 1.19.1.

### Исправлено

- **`build_external_object`: транзиентный «Infobase … is already connected» при первой сборке.** Первое обращение к ИБ проекта в свежем сеансе EDT переводит её в connected и при этом падает (`checkArgument` в стратегии синхронизации), а повтор проходит. Теперь этот отказ ретраится один раз автоматически — первый вызов больше не падает. Если и повтор упал — выдаётся честная ошибка с оригинальным текстом EDT и подсказкой закрыть сеансы ИБ.
- **`build_external_object`: честная диагностика отказов сервиса.** Раньше любой `IllegalArgumentException` от EDT трактовался как «проект не является проектом внешних отчётов и обработок», пряча реальную причину. Теперь оригинальное сообщение EDT сохраняется всегда.

### Проверено live (1.19.1)

Сборка `ExternalDataProcessor.ОчисткаКодовМаркировкиКПередаче` в `DandyВнешниеОбработки` штатным сервисом `IExternalObjectDumper` — валидный `.epf` (~9.9 КБ, контейнер 1С 8.3), без `1cv8 DESIGNER` и `serviceInfobase`. Скоуп precheck на целевую обработку подтверждён: блокер соседней обработки сборку не остановил.

## [1.19.1] — 2026-07-17

Правка по итогам live-smoke 1.19.0.

### Исправлено

- **`build_external_object`: precheck блокировал сборку из-за ошибок в соседних обработках.** Проверка маркеров шла по всему проекту, а external-object проект держит обработки десятками — `.epf` собирается для одной, и битый BSL в соседней в этот файл не попадёт. На рабочем проекте `DandyВнешниеОбработки` сборка `ОчисткаКодовМаркировкиКПередаче` отклонялась блокером из обработки «тест». Теперь precheck скоупится на каталог собираемой обработки (`src/<Тип>/<Имя>`).

### Проверено live (1.19.0)

`create_project type=configuration` → `Configuration.mdo` создаётся из seed, проект распознаётся (`type: configuration`), `create_md_object` работает (раньше падал «namespace may not be null»), объект виден в BM-модели.

## [1.19.0] — 2026-07-17

Завершение работ по итогам аудита: `build_external_object` переведён на штатное
API EDT, устранён дефект `create_project` для конфигураций.

### Изменено

- **`build_external_object` — на штатный сервис EDT вместо спавна `1cv8 DESIGNER`.** Сборку `.epf`/`.erf` теперь делает `IExternalObjectDumper` (пакет `com._1c.g5.v8.dt.platform.services.core.dump`) — тот же путь, что за «Экспортом» в IDE: он сам выгружает Designer-XML, находит информационную базу через связанное с проектом приложение, подставляет учётку и версию платформы и убирает временный каталог. Прежний костыль (экспорт в XML + резолв `1cv8.exe` + запуск `DESIGNER /LoadExternalDataProcessorOrReportFromFiles` + разбор `/Out`-лога) требовал свободной служебной ИБ. Из схемы инструмента убраны параметры `serviceInfobase` и `platformVersion` — информационная база берётся из ассоциации проекта (`associate_infobase`); её отсутствие диагностируется с подсказкой. Precheck по маркерам, таймаут и проверка результата на диске сохранены.

### Исправлено

- **`create_project type=configuration` создавал проект без `Configuration.mdo`.** Вызов шёл с пустым seed (`cpm.create(name, version, null, …)`), а EDT в этом случае пропускает создание контекста конфигурации — файл `Configuration.mdo` не писался, BM-namespace не активировался, и последующий `create_md_object` падал «namespace may not be null». Теперь инструмент передаёт seed `Configuration` (имя, вариант языка Russian, один язык по умолчанию), как это делает мастер «Новая конфигурация» в IDE.

## [1.18.1] — 2026-07-17

Правки по итогам live-smoke версии 1.18.0 на рабочем workspace.

### Исправлено

- **`build_external_object` отказывался собирать почти любую реальную обработку.** Блокером считался любой маркер `severity=error` с `checkId=BslEditor`, но EDT кладёт в ту же серьёзность стилевые SSL-замечания, deprecation, security-guideline и Web-клиентские несоответствия типов — компиляцию они не ломают. На живой обработке сборка отклонялась из-за «Метод устарел» и «Описание экспортируемой функции должно содержать блок "Возвращаемое значение"». Теперь блокер — компиляционная ошибка BSL: `BslEditor` + `error`, кроме известного списка стилевых сообщений. Незнакомая диагностика по-прежнему блокирует сборку (fail-closed).
- **`build_external_object`: поиск корневого XML выгрузки.** Раскладку задаёт EDT, и она не обязана быть плоской — на живом проекте на верхнем уровне `.xml` не оказалось вовсе. Поиск `<Имя>.xml` стал рекурсивным (от самых верхних уровней), а при неудаче сообщение перечисляет фактическое содержимое каталога выгрузки — иначе диагностировать нечем: каталог временный.

### Проверено live (v1.18.0)

`deploy_project` на EDT 2026.1 (главная болевая точка) — успешно; чтение табличных частей и значений перечислений; `ChartOfCharacteristicTypes` (был «unknown kind»); external-object проект в `list_md_objects` (была ошибка «cannot resolve Configuration root»); `query_event_log order=date_desc`; обезличивание пользователя в журнале регистрации; реальный путь в маркерах.

## [1.18.0] — 2026-07-17

По итогам глобального аудита v1.17.0 (2 BLOCKER, 13 MAJOR, ~30 MINOR).
Итого **97 инструментов** в 12 tool-бандлах.

### Исправлено

- **BLOCKER: `deploy_project` не работал на 1C:EDT 2026.x.** `IInfobaseSynchronizationManager.resolveInfobaseChanges` расходится по возвращаемому типу между ветками EDT — прямой вызов давал `ClassCastException` в `NoopUpdateCallback`. Вызов переведён на рефлексию с фолбэком.
- **BLOCKER: `deploy_project` не работал на всей ветке 1C:EDT 2023.x.** `IInfobaseSynchronizationManager.isConnected` отсутствует в `dt.platform.services.core` 18/19 → `NoSuchMethodError` ещё до `updateInfobase`. Та же схема: рефлексия + фолбэк (при отсутствии метода `connectInfobase` зовётся вслепую, а отказ «уже подключена» не роняет деплой). Обе точки dual-version теперь закрыты; live-smoke на 2023.x **не проводился** — см. матрицу поддержки в `README.md`.
- **`run_tests`: честный таймаут.** Процесс убивается через `destroyForcibly` + асинхронный дренаж потоков; в результате появился признак `killed`. Раньше executor заклинивал.
- **Обезличивание ПДн (152-ФЗ) — закрыты обходы:**
  - `returnsInfobaseData` добавлен `run_tests`, `run_test_method`, `set_variable` — стало **7** помеченных каналов (было 4);
  - маскируются и **тексты ошибок** инструментов, возвращающих данные ИБ (BM/EMF/debug-движок вкладывают в message фрагменты значений базы);
  - ключ инфобазы для per-infobase флага берётся из `IMcpTool.privacyInfobaseKey(args)`, а **не** из сырых `args` — раньше клиент мог дописать в аргументы debug-инструмента посторонний `name` «безопасной» базы и отключить обезличивание;
  - `data.value` журнала регистрации маскируется без привязки к каталогу.
- **`query_event_log`:** починен порядок `date_desc`, добавлена толерантность к рваному хвосту активной `.lgp` (новый ключ `partial`).
- **`check_list_markers` / `check_run`:** аргумент `path` стал настоящим фильтром, DTO несёт реальный путь маркера (раньше путь был фиктивным).
- **`add_test_method`:** честный `registered` + `warning` вместо безусловного «зарегистрирован».
- **Буква «Ё»** не ловилась regex-диапазоном `[А-Яа-я]` (в Unicode она вне диапазона) — починено в `TypeStringParser` и `BslAstReader`.
- **`add_attribute`:** поддержка составных (multi-type) типов; ожидание асинхронной BM-сериализации `.mdo`; кавычки в строке подключения `CREATEINFOBASE`.
- **Отладка:** cleanup при падении запуска — `dbgs`/target/breakpoint больше не утекают.
- MINOR-пакет по `core`/`ui`: версия сервера, secure store, кэш каталога ПДн, аудит, cleanup.

### Добавлено

- **`add_enum_value`** — добавление значения в перечисление.
- **`build_external_object`** — сборка внешнего объекта в `.epf`/`.erf` (EDT-проект → Designer XML → `1cv8 DESIGNER`). Обязательный precheck: отказывается собирать при BSL-ошибках компиляции — **ни один шаг пайплайна синтаксис не проверяет**.
- `list_attributes` / `get_md_object` возвращают дополнительные ключи `tabularSections` (табличные части с колонками) и `values` (значения перечисления).
- `list_md_objects` работает на external-object проектах (файловый скан `src/`).
- Единый реестр kind'ов метаданных: все **19** borrow-kind'ов + флаг `creatable`. `create_md_object` отбивает не-creatable вид с подсказкой использовать `borrow_md_object`; `borrow`/`list`/`get`/`add_attribute` согласованы между собой.
- `query_event_log`: параметры `session`, `offset`, `order`.

### Известные ограничения

- Схемы с `anyOf`/`oneOf` (`query_event_log`, `get_event_log_path`) MCP SDK клиенту **не публикует** — record `JsonSchema` не имеет таких полей. Требование «указать ровно один из `name` / `uuid` / `logDir`» продублировано словами в `description` инструмента.

## [1.17.0] — 2026-07-04

### Добавлено

- **Слой обезличивания персональных данных (152-ФЗ).** Централизованный fail-closed слой (`ru.fedukhin.edt.mcp.core.privacy` + новый bundle `ru.fedukhin.edt.mcp.tools.privacy`), который **перед отправкой ответа MCP-клиенту** (LLM / внешнее облако — трансграничная передача по ст. 12) псевдонимизирует или скрывает ПДн физлиц, специальные категории, биометрию и сведения о контрагентах/организациях. MCP-сервер видит реальные данные 1С только внутри процесса.
  - **Каналы**: обезличиваются только инструменты, реально возвращающие данные информационной базы и помеченные `IMcpTool.returnsInfobaseData()` — `get_variables`, `evaluate`, `get_stack`, `query_event_log`. Остальные инструменты (метаданные, код, формы) через фильтр не проходят.
  - **Точка внедрения** — единый `ToolSpecAdapter`: результат `tool.call()` проходит через `PrivacyRedactor` перед сериализацией.
  - **Детектирование (3 слоя, fail-closed)**: каталог типов/объектов проекта `.mcp/pii-catalog.json` → словарь имён реквизитов/переменных (СНИЛС/Паспорт/ИНН/ОГРН/…) → content-regex по свободным строкам (email, телефон, СНИЛС, ОГРН/ИНН, паспорт).
  - **Маскирование**: HMAC-псевдоним вида `Физлицо#a3f2` (ключ — в `SecureTokenStore`, детерминированный, **без обратной таблицы token→значение**); спец-категории и биометрия — полное сокрытие. Тип и структура ответа не меняются.
  - **Конфигурация**: per-project каталог + per-infobase флаг `containsRealPersonalData` (дефолт `true` = fail-closed) + журнал обезличивания без самих ПДн.
  - **4 новых инструмента**: `build_pii_catalog` (авто-посев каталога по метаданным проекта), `get_pii_catalog`, `set_infobase_pii_flag`, `get_privacy_audit`.
  - Соответствие 152-ФЗ: ст. 3 п.9 (обезличивание без обратной таблицы), ст. 5 (минимизация), ст. 10/11 (спец. категории и биометрия — полное сокрытие), ст. 12 (обезличивание до трансграничной передачи), ст. 18.1/19 (перечень в git + журнал).
  - Известные ограничения v1: ФИО в свободной строке с нейтральным именем переменной content-regex не ловит; авто-посев каталога охватывает справочники и документы (не регистры) — каталог редактируется вручную.

## [1.16.1] — 2026-07-02

### Исправлено

- **Совместимость с 1C:EDT 2026.x (dt.platform.services.core 21+/23).** После обновления EDT инструмент `deploy_project` (и, как следствие, весь цикл прогона xUnit) падал с `NoSuchMethodError`, потому что в новой платформе изменились сигнатуры ряда API. Все правки сделаны версионно-независимыми — старые версии EDT продолжают работать:
  - `IInfobaseSynchronizationManager.updateInfobase(...)` сменил возвращаемый тип `boolean` → `IStatus`. Вызывается через рефлексию (сигнатура рефлексии не включает возвращаемый тип, поэтому один байткод работает на обеих ветках); результат интерпретируется как `Boolean` (старый EDT) или `IStatus` (`ERROR` → ошибка deploy, `CANCEL` → неуспех, `OK`/`WARNING`/`INFO` → успех).
  - `IInfobaseUpdateCallback.resolveInfobaseChanges(...)` получил дополнительный параметр `Set<String>`. Headless-заглушка `NoopUpdateCallback` переписана со статического `implements` на динамический `java.lang.reflect.Proxy` — он реализует интерфейс ровно так, как тот загружен в текущем рантайме, независимо от числа аргументов метода.
  - В отладочном bundle методы `IBslValue.getDetailString()`, `IBslStackFrame.getLineNumber()`, `IBslStackFrame.getVariables()` больше не объявляют `throws DebugException` — узкие `catch (DebugException)` стали «unreachable». Расширены до `catch (Exception)` (достижимо и корректно на обеих версиях EDT).

## [1.16.0] — 2026-06-16

### Добавлено

- **Внешние объекты (Phase B).** Новый инструмент `create_external_object` создаёт `ExternalDataProcessor` / `ExternalReport` в проекте внешних объектов (nature `V8ExternalObjectsNature`). Реализация файловая (у внешних объектов нет Configuration-контейнера, а EDT API умеет создавать только новый проект с объектом-семенем): пишется `src/<Folder>/<Name>/<Name>.mdo` (skeleton с `producedTypes/objectType` + `containedObjects` с `classId` соответствующего MdClass — `c3831ec8…` для обработки, `e41aff26…` для отчёта) и пустой `ObjectModule.bsl`. Args: `project, kind, name, synonym?, comment?`.
- **Генератор макетов печатных форм (Phase B).** Новый инструмент `add_md_template` создаёт spreadsheet-макет `.mxlx` по структурному спеку `columns`/`rows` и регистрирует его в `.mdo` владельца как `<templates>`. `ownerFqn = '<Kind>.<Name>'` (ExternalDataProcessor/ExternalReport/DataProcessor/Report/Catalog/Document/…). Ячейки поддерживают `text|parameter`, объединение через `span`/`rowSpan` (→ `<merge>`), `bold`, `size`, `align` (left/center/right/justify), `valign` (top/center/bottom), `wrap`. Генератор — Java-порт проверенного прототипа (вёрстка подтверждена PDF-рендером в Phase A); `<i>` на ячейках не используется, объединение задаётся отдельными `<merge>` (0-based `r`/`c`, `w`=span−1). `overwrite=false` по умолчанию не трогает существующий `Template.mxlx`.

## [1.15.5] — 2026-05-31

### Добавлено

- На странице **Preferences → EDT MCP** появились кнопки **Start / Stop / Restart**. Дублируют меню `Window → EDT MCP` — выполняются через тот же `ICommandService`. Удобно когда страница уже открыта.

### Исправлено

- Status-bar item в трим-bar'е EDT теперь занимает одну строку (горизонтальный layout). Раньше использовался `GridLayout` c `GridData(FILL, CENTER, false, true)` — `grabExcessVerticalSpace=true` заставлял Label расти по высоте, текст уходил в перенос на узких трим-bar'ах. Заменено на `FillLayout(SWT.HORIZONTAL)`; в `onStateChange` добавлен `label.pack()`, чтобы виджет занимал только нужную ширину.

## [1.15.4] — 2026-05-31

### Исправлено

- Иконка `MCP: stopped` теперь действительно появляется в правом нижнем углу EDT 2026.1 после установки и рестарта IDE. В Eclipse 4.x compatibility layer не маппит legacy URI `toolbar:org.eclipse.ui.trim.status`, поэтому старая контрибуция статус-бара никогда не рендерилась (а меню `Window → EDT MCP` работало и работает). Status-bar item переведён на e4-native подход: `fragment.e4xmi` + `Addon` (`McpStatusBarAddon`) программно добавляет `MToolControl` в bottom `MTrimBar` каждой `MTrimmedWindow`; `McpStatusBarControl` — e4-DI рендерер с тем же поведением и палитрой цветов (stopped/starting → серый, `MCP :<port> ●` → зелёный, error → красный с tooltip'ом). Клик по элементу открывает `Preferences → EDT MCP`.

### Изменено

- `McpUiPlugin.start()` логирует "instance bound" в Platform log — диагностика для будущих случаев «плагин активирован, но виджет не виден».
- Старые классы `McpStatusBarItem` и `McpUiEarlyStartup` помечены `@Deprecated(forRemoval=true)` — не подключены, dead code, будут удалены в следующем major-релизе.

## [1.15.3] — 2026-05-31

### Исправлено

- `set_md_property property=defaultForm value=Catalog.X.Form.Y` теперь работает на nested formах объектов (Catalog/Document/Report/DataProcessor/Registers и т.п.). Раньше резолв падал с `md object not found`, потому что у объектов форма — nested element, а не top-object в BM. Tool парсит FQN и резолвит через `owner.getForms()`. `CommonForm.X` работал и раньше.

### Изменено

- Скиллы Claude Code (`.claude/skills/edt-mcp/SKILL.md` + `README.md`) вычищены от ссылок на закрытые баги — секция «Known bugs» и inline-маркеры `СМ. BUG-N` заменены на описание реального поведения tool'ов. Осталось одно известное ограничение EDT-редактора (extension-реквизиты на adopted Document, выведенные на форму).

## [1.15.2] — 2026-05-31

### Исправлено

- `set_form_handler` теперь дописывает в `Module.bsl` BSL stub-процедуру с правильной аннотацией (`&НаСервере` / `&НаКлиенте`) и стандартной сигнатурой по event'у (русские имена параметров: `Отказ`, `СтандартнаяОбработка`, `ТекущийОбъект`, `ПараметрыЗаписи` и т.д.). Покрыто 33 типовых form-level и item-level event'а + commands. Идемпотент по имени процедуры — re-bind не дублирует. Результат содержит `stubAdded: boolean`.
- `set_md_property` принимает универсальное `defaultForm` для 11 kind'ов (Catalog/Document/Report/DataProcessor/InformationRegister/AccumulationRegister + плановые/процессные). Значение — FQN формы; внутри dispatch по kind на правильный setter (`setDefaultObjectForm` / `setDefaultForm` / `setDefaultRecordForm` / `setDefaultListForm`).

### Добавлено

- Юнит-тесты: 28 новых cases (`FormHandlerStubFactoryTest`, `SetFormHandlerHasProcedureTest`, `PropertyAccessorDefaultFormTest`). Всего 685 → 713 PASS.

## [1.15.1] — 2026-05-31

### Исправлено

- Версия `feature.xml` (1.15.0) и `feature/pom.xml` (1.14.0-SNAPSHOT) разошлись — `mvn verify` падал на `tycho-packaging-plugin:validate-version`. Версии выровнены до 1.15.0-SNAPSHOT.
- В README поправлен дефолт `Auto-start on IDE launch` — должно быть `off`.

### Добавлено

- `README.md`: пошаговый «Быстрый старт с нуля» — от установки EDT и сборки p2-репозитория до подключения сервера из Claude Code / Claude Desktop / своего MCP-клиента.
- `CONTRIBUTING.md` — требования к окружению, Maven/Tycho-сборка, релизный процесс. Архитектурный блок вынесен из README сюда.

### Изменено

- `docs/tools.md` приведён к 89 инструментам (было 84). Добавлены секции `tools.eventlog` (2) и три Stage 8g DCS-settings tools (`add_dcs_setting_grouping`, `add_dcs_setting_filter`, `set_dcs_setting_parameter_value`).
- Скиллы `edt-mcp` и `edt-skd` синхронизированы с актуальным каталогом 89 тулов; в `edt-skd` обновлена секция «Чего инструментов нет» (3 settings tools теперь покрыты).

## [1.15.0] — 2026-05-31

### Добавлено

- Новый bundle `tools.eventlog` — работа с журналом регистрации `.lgf` / `.lgp` (legacy text v2.0):
  - `get_event_log_path` — путь к `1Cv8Log` и список партиций для file- и server-инфобаз.
  - `query_event_log` — запрос событий с фильтрами (период, пользователь, событие, важность, подстрока в комментарии и метаданных).

Итого 89 MCP-инструментов.

## [1.14.0] — 2026-05-31

### Изменено

- `get_form` читает форму с диска через `FormFileReader` (минуя BM-модель). Сразу после мутации возвращает актуальное состояние без ожидания асинхронной BM-синхронизации.

## [1.13.0] — 2026-05-31

### Добавлено

- `borrow_md_object` для catalog'а с `<owners>` cascade-заимствует все owner-catalog'и (recursive с защитой от циклов). Возвращает `cascadedOwners` со списком захваченных в процессе.
- 3 инструмента редактирования `settingsVariant` схемы компоновки (Stage 8g):
  - `add_dcs_setting_grouping` — группировка по полю.
  - `add_dcs_setting_filter` — условие отбора.
  - `set_dcs_setting_parameter_value` — значение параметра.

## [1.12.0] — 2026-05-26

### Добавлено

- Расширение DCS-инструментов: `add_dcs_calculated_field`, `add_dcs_total_field`, `add_dcs_dataset_link`, `set_dcs_query_text`. Теперь можно собирать схему компоновки данных из MCP-клиента целиком.
- `borrow_form_pictures` — автопоиск ссылок на `CommonPicture` в заимствованной форме и заимствование недостающих в расширение (закрывает «XDTO Picture mismatch» на адаптированных формах).

Итого 84 инструмента, после удаления 4 redundant tools (`delete_project`, `import_project`, `delete_infobase`, `check_suppress_add`).

## Раньше

История до v1.12.0 разработки внутренняя; первая публичная редакция отражает состояние v1.12.0.

[1.18.1]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.18.1
[1.18.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.18.0
[1.17.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.17.0
[1.16.1]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.16.1
[1.16.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.16.0
[1.15.5]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.5
[1.15.4]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.4
[1.15.3]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.3
[1.15.2]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.2
[1.15.1]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.1
[1.15.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.0
[1.14.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.14.0
[1.13.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.13.0
[1.12.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.12.0
