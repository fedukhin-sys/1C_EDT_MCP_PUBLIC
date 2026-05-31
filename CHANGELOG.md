# История изменений

Все значимые изменения публичной версии EDT_MCP. Формат — [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версии — по [семантическому](https://semver.org/lang/ru/) принципу.

## [Unreleased]

### Добавлено

- **p2 update site через GitHub Pages.** Плагин можно ставить и обновлять по URL
  `https://fedukhin-sys.github.io/1C_EDT_MCP_PUBLIC/` (Help → Install New Software),
  без сборки из исходников.
- GitHub Actions workflow `.github/workflows/publish-p2.yml`: по тегу `vX.Y.Z`
  собирает p2 на self-hosted runner (нужна установленная 1C:EDT) и публикует
  composite-репозиторий в ветку `gh-pages` + ZIP в Release.
- Скрипты `scripts/*.ps1` (локальная сборка, подмена пути к p2-пулу через
  `EDT_POOL_PATH`, генерация composite, публикация) и `docs/p2-publishing.md`.
- README: раздел «Установка из update site».

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
