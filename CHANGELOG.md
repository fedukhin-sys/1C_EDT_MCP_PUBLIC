# История изменений

Все значимые изменения публичной версии EDT_MCP. Формат — [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версии — по [семантическому](https://semver.org/lang/ru/) принципу.

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

[1.15.5]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.5
[1.15.4]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.4
[1.15.3]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.3
[1.15.2]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.2
[1.15.1]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.1
[1.15.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.15.0
[1.14.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.14.0
[1.13.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.13.0
[1.12.0]: https://github.com/fedukhin-sys/EDT_MCP/releases/tag/v1.12.0
