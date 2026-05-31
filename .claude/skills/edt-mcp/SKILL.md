---
name: edt-mcp
description: Manage 1C:EDT extension/configuration projects via the EDT_MCP MCP server — create metadata objects, borrow from parent config, build forms, write BSL, run checks, deploy infobases. Use whenever the user wants to author or modify a 1C:EDT project from outside the IDE.
---

# EDT_MCP — пользование MCP-сервером для 1C:EDT

EDT_MCP — это MCP-плагин для 1C:EDT, экспортирующий **89 инструментов** работы с проектами 1С:Предприятие через HTTP+SSE. Этот скилл — практический справочник: реальные имена параметров, рецепты для типовых задач, главные правила работы.

## TL;DR — как подключиться

1. **Сервер должен крутиться в EDT IDE.** Проверь: `Get-NetTCPConnection -State Listen -LocalPort 3001`. Если нет — пользователь запускает EDT, на стартапе плагин EDT_MCP сам поднимает сервер (порт настраивается в `Window → Preferences → EDT MCP`).
2. **Токен** — в `Window → Preferences → EDT MCP` (одна строка). Сохрани в файл, скажем `MCP_token.txt`.
3. **Endpoints**: SSE = `http://127.0.0.1:3001/mcp/sse`, messages = `http://127.0.0.1:3001/mcp/messages?sessionId=…` (sessionId возвращает SSE-handshake в первом `event: endpoint`).
4. **Тестировать через готовый harness**: см. секцию [Smoke harness](#smoke-harness).

## Архитектурные допущения

- **Проект** — это название Eclipse-проекта в workspace, на котором открыт EDT. Например `МояКонфигурация.Расширение`. Все tools принимают `project` как строку.
- **FQN** (fully qualified name) метаданных — формат `Kind.Name`, где `Kind` ∈ `{Catalog, Document, InformationRegister, AccumulationRegister, Constant, Enum, CommonModule, Role, Subsystem, DataProcessor, Report, ChartOfCharacteristicTypes, CommonForm, …}`. Примеры: `Catalog.Партнеры`, `Document.ЗаказКлиента`. Для вложенных — `Catalog.Контрагенты.TabularSection.КонтактнаяИнформация`, `Catalog.X.Form.Y`, `Catalog.X.Attribute.Y`/`Dimension.Y`/`Resource.Y`.
- **modulePath** — относительный путь от корня проекта: `src/Catalogs/X/ObjectModule.bsl`, `src/Catalogs/X/Forms/Y/Module.bsl`, `src/CommonModules/X/Module.bsl`, `src/Documents/X/RecordSetModule.bsl` и т.п.
- **Только русский identifier set** в этом проекте (если язык конфы Russian). Имена не транслитерируются.

## Полный список инструментов (89)

Точные имена аргументов получены из `tools/list`. Если параметра нет в списке `props` — он будет отвергнут (`additionalProperties: false`). Required помечены *.

### Workspace + projects (8)
| Tool | Args |
|---|---|
| `get_workspace_info` | — |
| `list_projects` | — |
| `list_runtime_versions` | — |
| `get_project` | `name*` |
| `list_project_files` | `name*`, `glob` |
| `open_project` | `name*` |
| `close_project` | `name*` |
| `create_project` | `name*`, `type*` (`configuration`/`extension`/`external-object`), `version*`, `parentConfigurationName` (для extension/external-object) |

### Infobase + deploy (5)
| Tool | Args |
|---|---|
| `list_infobases` | `folder`, `type` |
| `get_infobase` | `name`, `uuid` |
| `create_infobase` | `name*`, `type*`, `location*`, `version`, `folder`, `timeoutSeconds` |
| `associate_infobase` | `project*`, `infobase*`, `setDefault` |
| `deploy_project` | `project*`, `infobase*`, `force`, `timeoutSeconds` |

### Metadata (29)
| Tool | Args |
|---|---|
| `list_md_objects` | `project*`, `kind` |
| `get_md_object` | `project*`, `fqn*` |
| `create_md_object` | `project*`, `kind*`, `name*`, `synonym`, `comment` |
| `rename_md_object` | `project*`, `fqn*`, `newName*` |
| `set_md_property` | `project*`, `fqn*`, `property*`, `value*`, `path` |
| `list_attributes` | `project*`, `fqn*` |
| `add_attribute` | `project*`, `fqn*` (owner FQN), `name*`, `type*`, `role` (`Attribute`/`Dimension`/`Resource`), `synonym`, `comment` |
| `rename_attribute` | `project*`, `fqn*`, `oldName*`, `newName*`, `role` |
| `borrow_md_object` | `project*`, `fqn*` |
| `borrow_form` | `project*`, `parentFqn*` (parent MdObject FQN), `formName*` |
| `borrow_form_pictures` | `project*`, `parentFqn*` (**обязательно Form-FQN**: `Document.X.Form.Y` или `CommonForm.Y` — **не родитель**) |
| `add_tabular_section` | `project*`, `ownerFqn*`, `name*` |
| `add_tabular_section_attribute` | `project*`, `tsFqn*` (`Catalog.X.TabularSection.Y`), `name*`, `type*` |
| `add_extension_method_override` | `project*`, `modulePath*`, `source*` (полный текст процедуры с аннотацией `&Перед/&После/&ИзменениеИКонтроль`) |
| `add_register_recorder` | `project*`, `register*` (`AccumulationRegister.X`), `document*` (`Document.Y`) |
| `add_subsystem_content` | `project*`, `subsystemFqn*`, `contentFqn*` |
| `set_constant_type` | `project*`, `fqn*`, `type*` |
| `set_md_type` | `project*`, `fqn*` (`Kind.Owner.Attribute/Dimension/Resource.Name`), `type*` (string или массив) |
| `create_data_composition_schema` | `project*`, `reportFqn*`, `templateName` |
| `add_dcs_data_set_query` | `project*`, `reportFqn*`, `dataSetName*`, `query*`, `templateName`, `dataSource` |
| `add_dcs_field` | `project*`, `reportFqn*`, `dataSetName*`, `fieldName*`, `templateName`, `title` |
| `add_dcs_parameter` | `project*`, `reportFqn*`, `parameterName*`, `templateName`, `valueType`, `title` |
| `add_dcs_calculated_field` | `project*`, `reportFqn*`, `dataPath*`, `expression*`, `templateName`, `title` |
| `add_dcs_total_field` | `project*`, `reportFqn*`, `dataPath*`, `expression*`, `templateName`, `groupKeys` |
| `add_dcs_dataset_link` | `project*`, `reportFqn*`, `source*`, `destination*`, `sourceExpression*`, `destinationExpression*`, `templateName`, `parameter` |
| `set_dcs_query_text` | `project*`, `reportFqn*`, `dataSetName*`, `query*`, `templateName` |
| `add_dcs_setting_grouping` | `project*`, `reportFqn*`, `field*`, `templateName`, `variantName` (default `Основной`), `groupType` (`Items`/`Hierarchy`/`HierarchyOnly`, default `Items`) |
| `add_dcs_setting_filter` | `project*`, `reportFqn*`, `leftField*`, `comparisonType*` (`Equal`/`NotEqual`/...), `templateName`, `variantName`, `use` (default `false`) |
| `set_dcs_setting_parameter_value` | `project*`, `reportFqn*`, `parameterName*`, `templateName`, `variantName`, `value` (omitted ⇒ `xsi:nil`) |

### Eventlog (2)
| Tool | Args |
|---|---|
| `get_event_log_path` | `name` или `uuid` (oneOf*), `srvinfoDir`, `clusterPort` (для SERVER ИБ) |
| `query_event_log` | `name`/`uuid`/`logDir` (один из), `from`, `to`, `severity[]`, `user[]`, `userUuid[]`, `application[]`, `event[]`, `eventContains`, `commentContains`, `metadataContains`, `limit` (≤10000), `srvinfoDir`, `clusterPort` |

### BSL модули (5)
| Tool | Args |
|---|---|
| `read_module` | `project*`, `path*` |
| `write_module` | `project*`, `path*`, `content*`, `validate` (default true). Создаёт файл (и недостающие папки), если его ещё нет |
| `get_method` | `project*`, `path*`, `name*` |
| `list_module_methods` | `project*`, `path*` |
| `get_module_info` | `project*`, `path*` |

### Forms (11)
| Tool | Args |
|---|---|
| `list_forms` | `project*`, `parentFqn` |
| `get_form` | `project*`, `fqn*` |
| `get_form_item` | `project*`, `fqn*`, `itemPath*` |
| `create_form` | `project*`, `parentFqn*`, `name*`, `formType` (`ItemForm`/`Form`/…). Создаёт `Form.form`, пустой `Module.bsl`, `<commandInterface>` и проставляет форму основной у owner'а (kind-specific) |
| `add_form_attribute` | `project*`, `formFqn*`, `name*`, `type*`, `title`, `main` |
| `add_form_command` | `project*`, `formFqn*`, `name*`, `title`, `handlerName` |
| `add_form_field` | `project*`, `formFqn*`, `name*`, `dataPath*`, `parentPath`, `title` |
| `add_form_group` | `project*`, `formFqn*`, `name*`, `groupType*` (`Pages`/`Page`/`Group`/…), `parentPath`, `title` |
| `add_form_button` | `project*`, `formFqn*`, `name*`, `commandName*`, `parentPath`, `title` |
| `add_form_table` | `project*`, `formFqn*`, `name*`, `dataPath*`, `parentPath`, `title` |
| `set_form_handler` | `project*`, `formFqn*`, `event*`, `handlerName*`, `itemPath`. Прописывает связку в `Form.form` и дописывает в `Module.bsl` stub-процедуру с правильной аннотацией и стандартной сигнатурой по event'у (идемпотент по имени процедуры). Возвращает `stubAdded` (boolean) |

### Quality (4)
| Tool | Args |
|---|---|
| `check_catalog` | `filter`, `severity`, `source` |
| `check_describe` | `checkId*` |
| `check_run` | `project*`, `path`, `checkIds`, `waitSeconds`, `clearFirst` |
| `check_list_markers` | `project*`, `path`, `severity`, `checkId`, `source` |

### xUnit (8)
| Tool | Args |
|---|---|
| `create_test_module` | `project*`, `name*`, `language` |
| `add_test_method` | `project*`, `moduleFqn*`, `methodName*`, `body` |
| `list_test_modules` | `project*`, `language` |
| `get_test_methods` | `project*`, `moduleFqn*` |
| `install_test_runner` | `project*` |
| `uninstall_test_runner` | `project*` |
| `run_tests` | `project*`, `infobase*`, `moduleFqn`, `timeoutSeconds` |
| `run_test_method` | `project*`, `infobase*`, `moduleFqn*`, `methodName*`, `timeoutSeconds` |

### Client + debug (17)
| Tool | Args |
|---|---|
| `run_client` | `infobase*`, `clientType` (`thin`/`thick`), `user`, `password` |
| `list_running_clients` | `infobase`, `clientType` |
| `stop_client` | `sessionId*`, `force`, `gracefulTimeoutSeconds` |
| `debug_client` | `infobase*`, `clientType`, `user`, `password`, `stopOnError` |
| `stop_debug` | `debugSessionId*` |
| `list_debug_sessions` | — |
| `get_debug_state` | `debugSessionId*` |
| `set_breakpoint` | `project*`, `path*`, `line*`, `condition` |
| `list_breakpoints` | — |
| `remove_breakpoint` | `breakpointId*` |
| `get_stack` | `debugSessionId*`, `threadId*` |
| `get_variables` | `debugSessionId*`, `frameId*` |
| `evaluate` | `debugSessionId*`, `frameId*`, `expression*` |
| `set_variable` | `debugSessionId*`, `frameId*`, `variableName*`, `valueExpression*` |
| `debug_resume` | `debugSessionId*`, `timeoutSeconds` |
| `debug_pause` | `debugSessionId*` |
| `debug_step` | `debugSessionId*`, `threadId*`, `kind*` (`over`/`into`/`out`), `timeoutSeconds` |

## Типы (тип-выражения)

Используются в `add_attribute`, `set_md_type`, `set_constant_type`, `add_tabular_section_attribute`, `add_form_attribute`:

| Тип | Запись |
|---|---|
| Строка | `String(50)` (с длиной) или `String` (unlimited) |
| Число | `Number(10,2)` (precision, scale), `Number(10)` |
| Дата | `Date` |
| Булево | `Boolean` |
| УникальныйИдентификатор | `UUID` |
| ХранилищеЗначения | `ValueStorage` |
| Ссылка | `CatalogRef.Name`, `DocumentRef.Name`, `EnumRef.Name`, `ChartOfCharacteristicTypesRef.Name`, `ChartOfAccountsRef.Name`, `ExchangePlanRef.Name`, `BusinessProcessRef.Name`, `TaskRef.Name` |
| Любая ссылка | `AnyRef` |
| Составной тип | массив: `["CatalogRef.Партнеры", "CatalogRef.Контрагенты", "String(50)"]` через `set_md_type` |

`add_attribute` принимает строку или **массив строк** (составной тип). Если простой `add_attribute` для составного не сработал — добавь атрибут с одним типом, потом `set_md_type` массивом.

## Рецепты — типовые задачи

### Создание справочника с атрибутами и табличной частью
```jsonc
[
  { "tool": "create_md_object", "args": { "project": "X", "kind": "Catalog", "name": "ТестСправочник" } },
  { "tool": "add_attribute", "args": { "project": "X", "fqn": "Catalog.ТестСправочник", "name": "Сумма", "type": "Number(15,2)" } },
  { "tool": "add_attribute", "args": { "project": "X", "fqn": "Catalog.ТестСправочник", "name": "Партнер", "type": "CatalogRef.Партнеры" } },
  { "tool": "add_tabular_section", "args": { "project": "X", "ownerFqn": "Catalog.ТестСправочник", "name": "Состав" } },
  { "tool": "add_tabular_section_attribute", "args": { "project": "X", "tsFqn": "Catalog.ТестСправочник.TabularSection.Состав", "name": "Номенклатура", "type": "CatalogRef.Номенклатура" } }
]
```

### Заимствование объектов основной конфы в расширение
```jsonc
[
  { "tool": "borrow_md_object", "args": { "project": "X", "fqn": "Catalog.Партнеры" } },
  { "tool": "borrow_md_object", "args": { "project": "X", "fqn": "Document.ЗаказКлиента" } },
  { "tool": "borrow_form", "args": { "project": "X", "parentFqn": "Document.ЗаказКлиента", "formName": "ФормаДокумента" } },
  { "tool": "borrow_form_pictures", "args": { "project": "X", "parentFqn": "Document.ЗаказКлиента.Form.ФормаДокумента" } }
]
```
**Внимание:** `borrow_form_pictures` принимает **FQN формы** (`Document.X.Form.Y`), не родителя. Без `borrow_form_pictures` валидатор EDT ругается «Picture mismatch» на заимствованной форме.

### Override метода в адаптированном объекте
```jsonc
{ "tool": "add_extension_method_override", "args": {
   "project": "X",
   "modulePath": "src/Documents/ЗаказКлиента/ObjectModule.bsl",
   "source": "&После(\"ОбработкаПроведения\")\nПроцедура Расш_ОбработкаПроведения(Отказ, Режим)\n\tДвижения.МойРегистр.Записывать = Истина;\n\t...\nКонецПроцедуры\n"
} }
```
Аннотации: `&Перед("Имя")`, `&После("Имя")`, `&ИзменениеИКонтроль("Имя")`. Сигнатура процедуры **должна 1:1 совпадать с базовой** (включая параметры и их имена).

**`#Вставка` — препроцессорная директива, НЕ комментарий.** Внутри `&ИзменениеИКонтроль` правки помечаются `#Вставка` / `#КонецВставки` (вставка) и `#Удаление` / `#КонецУдаления` (удаление) — директивами с `#` в начале строки (column 0), **без** `//`. Тело `&ИзменениеИКонтроль` должно содержать **полную копию базового метода** + эти блоки (EDT сверяет с оригиналом).

**Обёртка `#Если` для методов модулей объектов:** методы `&После`/`&Перед`/`&ИзменениеИКонтроль` в `ObjectModule.bsl` адаптированного объекта обязательно обернуть в ТУ ЖЕ препроцессорную обёртку, что и базовый модуль (обычно `#Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда … #КонецЕсли`; бывает вложенной — напр. `Catalog.Номенклатура`: `#Если НЕ МобильныйАвтономныйСервер Тогда` + вложенный `#Если Сервер…`). Иначе — error «Метод расширения имеет большую видимость». Tool `add_extension_method_override` делает это автоматически — дублирует guard из базового `ObjectModule.bsl`.

Tool сам создаёт файл если его нет, дописывает в конец. Дубль по имени процедуры — auto-merge body (v1.10.5+).

### Форма элемента с группами и таблицей
```jsonc
[
  { "tool": "create_form", "args": { "project": "X", "parentFqn": "Catalog.ТестСправочник", "name": "ФормаЭлемента", "formType": "ItemForm" } },
  { "tool": "add_form_group", "args": { "project": "X", "formFqn": "Catalog.ТестСправочник.Form.ФормаЭлемента", "name": "Страницы", "groupType": "Pages" } },
  { "tool": "add_form_group", "args": { "project": "X", "formFqn": "...", "name": "Реквизиты", "groupType": "Page", "parentPath": "Страницы" } },
  { "tool": "add_form_field", "args": { "project": "X", "formFqn": "...", "name": "ПолеНаименование", "dataPath": "Объект.Наименование", "parentPath": "Страницы/Реквизиты" } },
  { "tool": "add_form_table", "args": { "project": "X", "formFqn": "...", "name": "ТЧ", "dataPath": "Объект.Состав", "parentPath": "Страницы/Таблица" } },
  { "tool": "add_form_field", "args": { "project": "X", "formFqn": "...", "name": "КолНоменклатура", "dataPath": "Объект.Состав.Номенклатура", "parentPath": "Страницы/Таблица/ТЧ" } }
]
```

### Обработчик формы + код в Module.bsl
```jsonc
[
  // 1. Tool привязывает имя метода в Form.form, но НЕ создаёт Module.bsl и НЕ пишет stub.
  { "tool": "set_form_handler", "args": {
    "project": "X",
    "formFqn": "Catalog.X.Form.ФормаЭлемента",
    "event": "OnCreateAtServer",
    "handlerName": "ПриСозданииНаСервере" } },
  // 2. Создай пустой Module.bsl вручную (через FS — write_module не создаёт новых файлов!)
  // 3. Запиши тело:
  { "tool": "write_module", "args": {
    "project": "X",
    "path": "src/Catalogs/X/Forms/ФормаЭлемента/Module.bsl",
    "content": "&НаСервере\nПроцедура ПриСозданииНаСервере(Отказ, СтандартнаяОбработка)\n\tСообщить(\"Привет\");\nКонецПроцедуры\n" } }
]
```
Список событий и аннотаций директивы:
- `OnCreateAtServer` — `&НаСервере`
- `OnOpen`, `OnClose` — `&НаКлиенте`
- `BeforeWrite`, `OnWrite`, `AfterWrite` — `&НаСервере`/`&НаКлиенте` в зависимости
- `Команда_X_Click` — `&НаКлиенте`

### Регистр сведений с независимой записью
```jsonc
[
  { "tool": "create_md_object", "args": { "project": "X", "kind": "InformationRegister", "name": "МойРС" } },
  { "tool": "add_attribute", "args": { "project": "X", "fqn": "InformationRegister.МойРС", "name": "Объект", "type": "AnyRef", "role": "Dimension" } },
  { "tool": "add_attribute", "args": { "project": "X", "fqn": "InformationRegister.МойРС", "name": "ДатаЗаписи", "type": "Date", "role": "Resource" } }
]
```
`writeMode=Independent` и `dataLockControlMode=Managed` ставятся **по умолчанию** при `create_md_object`. Менять `writeMode` потом — через `set_md_property property=writeMode value=Independent/RecorderSubordinate`.

### Регистр накопления с регистраторами
```jsonc
[
  { "tool": "create_md_object", "args": { "project": "X", "kind": "AccumulationRegister", "name": "МойРН" } },
  { "tool": "add_attribute", "args": { "project": "X", "fqn": "AccumulationRegister.МойРН", "name": "Документ", "type": "DocumentRef.ТестДокумент", "role": "Dimension" } },
  // составной тип: добавили один, потом расширили
  { "tool": "set_md_type", "args": { "project": "X", "fqn": "AccumulationRegister.МойРН.Dimension.Документ", "type": ["DocumentRef.ТестДокумент", "DocumentRef.ЗаказКлиента"] } },
  { "tool": "add_attribute", "args": { "project": "X", "fqn": "AccumulationRegister.МойРН", "name": "Количество", "type": "Number(15,3)", "role": "Resource" } },
  { "tool": "add_register_recorder", "args": { "project": "X", "register": "AccumulationRegister.МойРН", "document": "Document.ТестДокумент" } },
  { "tool": "add_register_recorder", "args": { "project": "X", "register": "AccumulationRegister.МойРН", "document": "Document.ЗаказКлиента" } }
]
```

### СКД-отчёт

> **Для отчётов на Системе компоновки данных — отдельный скилл [edt-skd](../edt-skd/SKILL.md).**
> Он содержит доменные паттерны СКД: реальный формат файла схемы `.dcs`, наборы данных
> (запрос/объект/объединение), роли полей и корректные остатки, ресурсы, вычисляемые поля,
> параметры периода, связи наборов, структуру настроек `settingsVariant`, условное оформление
> и программную компоновку из BSL. Ниже — только базовый MCP-рецепт; за паттернами и доводкой
> схемы иди в `edt-skd`.

```jsonc
[
  { "tool": "create_md_object", "args": { "project": "X", "kind": "Report", "name": "МойОтчет" } },
  { "tool": "create_data_composition_schema", "args": { "project": "X", "reportFqn": "Report.МойОтчет" } },
  // templateName по умолчанию = ОсновнаяСхема
  { "tool": "add_dcs_data_set_query", "args": {
    "project": "X", "reportFqn": "Report.МойОтчет", "dataSetName": "DataSet1",
    "query": "ВЫБРАТЬ ... ИЗ РегистрНакопления.X ГДЕ Период МЕЖДУ &НачалоПериода И &КонецПериода" } },
  { "tool": "add_dcs_parameter", "args": { "project": "X", "reportFqn": "Report.МойОтчет", "parameterName": "НачалоПериода", "valueType": "Date" } },
  { "tool": "add_dcs_parameter", "args": { "project": "X", "reportFqn": "Report.МойОтчет", "parameterName": "КонецПериода", "valueType": "Date" } },
  { "tool": "add_dcs_total_field", "args": { "project": "X", "reportFqn": "Report.МойОтчет", "dataPath": "Сумма", "expression": "Сумма(Сумма)" } }
]
```
**Текст запроса — только по реально существующим данным.** Перед тем как писать `query` в наборе данных СКД, проверь по `.mdo` (через `list_project_files` + Read), что КАЖДАЯ таблица, реквизит, поле ТЧ и стандартный атрибут реально существуют. Не выдумывай реквизиты по аналогии и не предполагай «стандартные» имена. Частые ловушки: `ЭтоГруппа`/`Родитель` есть только у иерархических справочников — у иерархии «только элементы» поля `ЭтоГруппа` НЕТ; имя реквизита-контрагента бывает `Контрагент`/`Партнер`/`Клиент`. **`check_run` ошибки в тексте запроса СКД НЕ ловит** — «Поле не найдено» вылезет только в рантайме 1С при открытии отчёта.

Назначить основную форму отчёта/справочника/документа: `set_md_property property=defaultForm value=Kind.X.Form.Y` (универсальное имя; dispatch по kind на `setDefaultObjectForm` / `setDefaultForm` / `setDefaultRecordForm` / `setDefaultListForm`).

### Subsystem (командный интерфейс)
```jsonc
[
  { "tool": "create_md_object", "args": { "project": "X", "kind": "Subsystem", "name": "МояПодсистема" } },
  { "tool": "add_subsystem_content", "args": { "project": "X", "subsystemFqn": "Subsystem.МояПодсистема", "contentFqn": "Catalog.ТестСправочник" } },
  { "tool": "add_subsystem_content", "args": { "project": "X", "subsystemFqn": "Subsystem.МояПодсистема", "contentFqn": "Document.ТестДокумент" } }
]
```
Для вложенной подсистемы `subsystemFqn`: `Subsystem.Родитель.Subsystem.Дочерняя` (полный nested FQN).

### Проверка качества + деплой
```jsonc
[
  { "tool": "check_run", "args": { "project": "X", "waitSeconds": 300 } },
  { "tool": "check_list_markers", "args": { "project": "X" } }
]
```
В `summary`: `{ blocker, critical, major, minor, trivial }`. **Деплоим только при blocker=0 и critical=0**. `major` SSL-стиля (см. ниже) — игнор.

**Категоризация маркеров** (см. CLAUDE.md проекта `EDT_MCP`):
- **BLOCKER** (фикс обязательно): `BslEditor` + сообщения про синтаксис/компиляцию («Ожидается имя переменной», «Ожидается выражение», «Встроенная функция…», «Данный модуль может содержать только процедуры и функции»), любые `summary.blocker`/`critical`.
- **WARN** (пропускаем): SSL-стиль («Описание Экспорт-функции должно содержать блок Возвращаемое значение», «Метод доступен НаКлиенте», «Метод необходимо разместить в одной из верхнеуровневых областей: ОписаниеПеременных…»), security guidelines, deprecation, Web-mismatch, `MdValidationChecker` про флаги модуля.

После фикса BLOCKER'ов — `check_list_markers` повторно, далее `associate_infobase` + `deploy_project`.

### Запуск клиента и тестов
```jsonc
[
  { "tool": "associate_infobase", "args": { "project": "X", "infobase": "МояИБ", "setDefault": true } },
  { "tool": "deploy_project", "args": { "project": "X", "infobase": "МояИБ", "force": true, "timeoutSeconds": 600 } },
  { "tool": "run_client", "args": { "infobase": "МояИБ", "clientType": "thin" } },
  // тесты xUnitFor1C:
  { "tool": "install_test_runner", "args": { "project": "X" } },
  { "tool": "create_test_module", "args": { "project": "X", "name": "МоиТесты", "language": "ru" } },
  { "tool": "add_test_method", "args": { "project": "X", "moduleFqn": "CommonModule.МоиТесты", "methodName": "Сценарий1", "body": "..." } },
  { "tool": "run_test_method", "args": { "project": "X", "infobase": "МояИБ", "moduleFqn": "CommonModule.МоиТесты", "methodName": "Тест_Сценарий1" } }
]
```

## Известное ограничение

**Extension attributes на adopted Document + form binding** — EDT-редактор формы подсвечивает «Объект.X 2 сегмент ссылается на неизвестный объект» для new attributes на adopted Document, выведенных на форму. `deploy` зелёный, расширение работает в runtime. Workaround: использовать собственный Document расширения (а не adopted) для new attributes с form binding'ом.

## Smoke harness

Минимальный JS-клиент для smoke-тестов (есть готовый в `E:\Claude\mcp-smoke.js` на target-машине; ниже — общий вариант).

```js
'use strict';
// usage: node mcp-smoke.js <token> <steps.json>
const http = require('http');
const TOKEN = process.argv[2];
const STEPS = require(process.argv[3]);
const BASE = 'http://127.0.0.1:3001';
let endpoint = null, nextId = 1, buffer = '';
const pending = new Map();
const vars = {};

const sse = http.get(BASE + '/mcp/sse', {
  headers: { 'Authorization': 'Bearer ' + TOKEN, 'Accept': 'text/event-stream' }
}, (res) => {
  if (res.statusCode !== 200) { console.error('HTTP ' + res.statusCode); process.exit(1); }
  res.setEncoding('utf8');
  res.on('data', (chunk) => {
    buffer += chunk.replace(/\r\n/g, '\n');
    let idx;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, idx); buffer = buffer.slice(idx + 2);
      let event = 'message', data = '';
      for (const ln of raw.split('\n')) {
        if (ln.startsWith('event:')) event = ln.slice(6).trim();
        else if (ln.startsWith('data:')) data += ln.slice(5).trim();
      }
      if (event === 'endpoint') { endpoint = data.startsWith('http') ? data : BASE + data; run(); }
      else if (event === 'message' && data) {
        try { const m = JSON.parse(data); if (pending.has(m.id)) { pending.get(m.id).resolve(m); pending.delete(m.id); } } catch(e){}
      }
    }
  });
});

function post(p) {
  const body = JSON.stringify(p), u = new URL(endpoint);
  return new Promise((res, rej) => {
    const r = http.request({ hostname: u.hostname, port: u.port, path: u.pathname + u.search, method: 'POST',
      headers: { Authorization: 'Bearer ' + TOKEN, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) }
    }, (rs) => { rs.resume(); rs.on('end', res); });
    r.on('error', rej); r.write(body); r.end();
  });
}

async function send(method, params) {
  const id = nextId++;
  const p = new Promise((res, rej) => {
    pending.set(id, { resolve: res, reject: rej });
    setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('timeout: ' + method)); } }, 600000);
  });
  await post({ jsonrpc: '2.0', id, method, params });
  return p;
}

async function run() {
  await send('initialize', { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'smoke', version: '1.0' } });
  await post({ jsonrpc: '2.0', method: 'notifications/initialized' });
  for (const step of STEPS) {
    console.log('## ' + (step.label || step.tool));
    const res = await send('tools/call', { name: step.tool, arguments: step.args || {} });
    const ts = (res.result && res.result.content || []).map(c => c.text || JSON.stringify(c)).join('\n');
    console.log('isError=' + !!(res.result && res.result.isError) + '\n' + ts);
  }
  process.exit(0);
}
```

Использование:
```powershell
$tok = (Get-Content "C:\path\to\MCP_token.txt" -Raw).Trim()
node "C:\path\to\mcp-smoke.js" $tok "C:\path\to\steps.json"
```

`steps.json` — массив `{ label, tool, args, expectError?, expectContains? }`. PowerShell ест внутренние кавычки в JSON-литералах — **всегда** используй внешний `.json` файл, не передавай JSON через CLI.

## Доводка объектов до стандартов 1С (наработки прогона)

MCP-инструменты создают **минимальные** объекты — для чистого прохода `check_run` их `.mdo`/`.bsl` приходится дополнять вручную. Что именно дописывалось:

### Модули объектов и форм (`.bsl`)
- **Обёртка `#Если`**: код модуля объекта (`ObjectModule.bsl`) — целиком в `#Если Сервер Или ТолстыйКлиентОбычноеПриложение Или ВнешнееСоединение Тогда … #КонецЕсли`. Условие копировать из БАЗОВОГО модуля (бывает вложенным — у некоторых объектов внешняя `#Если НЕ МобильныйАвтономныйСервер Тогда` + вложенный `#Если Сервер…`). Без обёртки — warning «Метод доступен НаКлиенте», для адаптированных объектов — error «большая видимость». `add_extension_method_override` делает это сам.
- **Области `#Область`**: процедуры — в верхнеуровневых областях. Модуль объекта — `#Область ОбработчикиСобытий`. Модуль формы — `#Область ОбработчикиСобытийФормы` / `ОбработчикиКомандФормы` / `СлужебныеПроцедурыИФункции`. Без областей — warning «Метод необходимо разместить в одной из верхнеуровневых областей».
- **Устаревшие методы** (warning «Используется не рекомендуемый метод»):
  - `Сообщить(Текст)` → на сервере `ОбщегоНазначения.СообщитьПользователю(Текст)`, на клиенте `ОбщегоНазначенияКлиент.СообщитьПользователю(Текст)`;
  - `ТекущаяДата()` → `ТекущаяДатаСеанса()`.

### Метаданные (`.mdo`)
- **Документ с проведением**: добавить `<postInPrivilegedMode>true</postInPrivilegedMode>` + `<unpostInPrivilegedMode>true</unpostInPrivilegedMode>` (после `<registerRecords>`, перед `<attributes>`). Иначе error «не стоит флаг Прив. режим при проведении/отмене проведения».
- **Документ** должен иметь реквизит `Комментарий` — `String`, с `<multiLine>true</multiLine>`. Иначе warning «Объект метаданных не имеет реквизит Комментарий».
- **Представления**: Catalog/Document — `<objectPresentation>` + `<listPresentation>`; InformationRegister — `<recordPresentation>` + `<listPresentation>`. Синонима НЕ достаточно (отдельный warning «Не заполнено ни представление объекта, ни представление списка»). Формат как у `<synonym>`.
- **Префикс имён**: если у расширения задан `<namePrefix>`, объекты без префикса дают warning «Имя объекта должно содержать префикс "X"». Либо именовать с префиксом, либо принять как minor.

### Роли — `setForNewObjects`
`<setForNewObjects>true</setForNewObjects>` в `Rights.rights` авто-выдаёт новым объектам ВСЕ права, включая `Delete`/`InteractiveDelete` → errors `MdValidationChecker` «Право Удаление/ИнтерактивноеУдаление роли установлено для …». Фикс: `<setForNewObjects>false</setForNewObjects>` + явные блоки `<object><name>Kind.X</name><right><name>Read</name><value>true</value></right>…</object>` без delete-семейства прав. Порядок в `.rights`: сначала три флага (`setForNewObjects`, `setForAttributesByDefault`, `independentRightsOfChildObjects`), затем блоки `<object>`.

### Формы
- `create_form` сам пишет `<commandInterface>` и проставляет форму основной (`<defaultObjectForm>`/`<defaultForm>`/`<defaultRecordForm>`/`<defaultListForm>` — kind-specific).
- Extension-реквизит адаптированного документа, выведенный на ЗАИМСТВОВАННУЮ форму, EDT может пометить «Путь к данным … N сегмент … ссылается на неизвестный объект». Помогает добавить в заимствованный `Form.form` явный атрибут `Объект` (`<attributes><name>Объект</name><id>1</id><valueType><types>DocumentObject.X</types></valueType><view><common>true</common></view><edit><common>true</common></edit><main>true</main><savedData>true</savedData></attributes>`) + пересборка модели (`close_project`/`open_project`).

### EDT / окружение
- **Рассинхрон модели EDT** после удаления/пересоздания объектов (редактор показывает чужое содержимое формы либо не отрисовывает её) — лечится `close_project` + `open_project`. Файлы на диске при этом обычно корректны — проверяй их прежде, чем «чинить».
- **`.metadata/.log`** EDT-воркспейса — первый источник РЕАЛЬНОЙ причины, когда форма/редактор «не работает». Грепай по имени объекта / `Exception`.
- **MCP-сервер может отвалиться**: порт 3001 перестаёт слушаться при работающем EDT. Лечится перезапуском EDT / MCP-сервера. После рестарта порт слушается, но первые секунды соединение таймаутит (`ETIMEDOUT`) — опрашивай с паузой.
- **Отладка**: `debug_client` ловит клиентские точки останова (`&НаКлиенте`); серверные (`&НаСервере`, код модулей объектов в rphost) этой сессией не перехватываются. `get_debug_state` → `state: suspended` + `location` = точка сработала, `state: running` = не сработала. Снятый из проекта breakpoint остаётся в активной debug-сессии — для полной очистки `stop_debug`.

## Главные правила работы

1. **Перед `deploy_project`** — обязательно `check_list_markers`, фиксь только BLOCKER (см. категоризацию выше).
2. **После любой мутации** через MCP — на критичных шагах ориентируйся на содержимое `.mdo` на диске: BM-модель обновляется асинхронно, дисковая запись — синхронная и достоверная.
3. **EDT BM async**: после `create_project`/`open_project` сразу `list_md_objects` может вернуть `namespace inactive`. Опрашивай с интервалом 5-10s до 90 секунд.
4. **1cv8 процессы**: deploy запускает 1cv8 DESIGNER который захватывает infobase. Между двумя deploys убедись что предыдущий 1cv8 завершён (`Get-Process 1cv8 | Stop-Process -Force`). Авторизуй убийство процессов у пользователя заранее.
5. **Кодировка**: EDT-файлы (`.mdo`, `.bsl`, `.form`) **только UTF-8 без BOM**. В PowerShell `Set-Content -Encoding utf8` пишет **с BOM** и double-encoding'ом, кириллица превращается в `Р`-иероглифы. Используй `[System.IO.File]::WriteAllText(path, content, [System.Text.UTF8Encoding]::new($false))` или MCP-`write_module`.
6. **CRLF vs LF**: `.env`, `.bsl` пиши с LF. На Windows `Set-Content` без `-NoNewline` ставит CRLF, что иногда ломает парсеры.
7. **Расширение метода (override)** — сигнатура процедуры **1:1** с базовой (имена параметров тоже). Иначе валидатор EDT отвергнет.
8. **Объекты регистра**: для AccumulationRegister/InformationRegister `add_attribute` обязательно указать `role: "Dimension"` или `role: "Resource"`. Без role — создастся `Attribute`, что для регистра бессмысленно.
9. **Inactive после кражи 1cv8**: если 1cv8 DESIGNER крашится посредине, EDT BM остаётся в неконсистентном состоянии. Помогает `close_project` + `open_project` + опрос BM.
10. **Расположение конфы** — для extension `parentConfigurationName` обязателен (имя родительской конфигурации). Если родителя не указать — extension не привяжется и deploy будет жаловаться на UUID-маппинг.
11. **Текст запроса — только по фактическим метаданным.** Перед написанием любого запроса (наборы данных СКД, `add_dcs_data_set_query`, `set_dcs_query_text`, запросы в BSL) сверь по `.mdo`, что все таблицы, реквизиты и поля ТЧ существуют — НЕ выдумывай реквизиты «по аналогии» и не угадывай «стандартные» имена. `check_run` ошибки запроса СКД не ловит — «Поле не найдено» всплывёт только в рантайме 1С.

## Что НЕ работает / не покрыто

- **Деплой может зависнуть** при «грязной» BM-сериализации. Если deploy висит больше 5-10 минут — отмени, кильни 1cv8, перезапусти.
- **xUnit run_tests/run_test_method** — требуют предварительного `install_test_runner` на проекте.
- **debug_client + breakpoints** — рабочая цепочка, но требует тёплый infobase (deploy успешен, конфа актуальна). Если не получается attach — проверь `deploy_project` сначала.

## Памятки

- Проект, в котором ты сейчас работаешь: смотри `list_projects` + `get_project name=X` (поле `exists`, `open`).
- Infobase: `list_infobases` + `get_infobase`. Имя — то, что в EDT-IDE в `Window → 1C:Enterprise → Infobases`.
- Token rotation: `Window → Preferences → EDT MCP → Regenerate token` — после ротации старый перестаёт работать, нужно обновить файл.
- Прод-сервер пишет токен в Equinox secure prefs, не в открытом виде; забирать **только** через preference page.
