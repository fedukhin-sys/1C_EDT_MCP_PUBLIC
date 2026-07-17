# Полный список инструментов EDT_MCP (97)

Точные имена аргументов получены из `tools/list` и сверены с `inputSchema()` в коде.
Если параметра нет в списке — он будет отвергнут (`additionalProperties: false`).
Required помечены `*`.

Сверить актуальный набор — `tools/list`; счётчик по бандлам в репо:
`grep -c "<tool " bundles/*/plugin.xml`.

## Workspace + projects (8)
| Tool | Args |
|---|---|
| `get_workspace_info` | — |
| `list_projects` | — |
| `list_runtime_versions` | — |
| `get_project` | `name*` |
| `list_project_files` | `name*`, `glob` |
| `open_project` | `name*` |
| `close_project` | `name*` |
| `create_project` | `name*`, `type*` (`configuration`/`extension`/`external-object`), `version*`, `parentConfigurationName` (для extension/external-object), `namePrefix` (префикс имён объектов расширения — закрывает warning «Имя объекта должно содержать префикс») |

## Infobase + deploy (5)
| Tool | Args |
|---|---|
| `list_infobases` | `folder`, `type` |
| `get_infobase` | `name`, `uuid` |
| `create_infobase` | `name*`, `type*`, `location*`, `version`, `folder`, `timeoutSeconds` |
| `associate_infobase` | `project*`, `infobase*`, `setDefault` |
| `deploy_project` | `project*`, `infobase*`, `force`, `timeoutSeconds` |

## Metadata (33)
| Tool | Args |
|---|---|
| `list_md_objects` | `project*`, `kind`. Знает все 19 borrow-kind'ов; на external-object проектах читает `src/` файловым сканом |
| `get_md_object` | `project*`, `fqn*`. Возвращает в т.ч. `tabularSections` (ТЧ с колонками) и `values` (значения Enum) |
| `create_md_object` | `project*`, `kind*`, `name*`, `synonym`, `comment`. С 1.20.0 создаются и Task/BusinessProcess/ChartOf*/ExchangePlan/DocumentJournal; не-creatable остались CommonForm и CommonPicture (нужны Form.form/файл картинки) — отбиваются с подсказкой использовать `borrow_md_object` |
| `create_external_object` | `project*` (проект внешних объектов), `kind*` (`ExternalDataProcessor`/`ExternalReport`), `name*`, `synonym`, `comment` |
| `build_external_object` | `project*`, `fqn*`, `outPath*`, `timeoutSeconds`. Сборка `.epf`/`.erf` штатным экспорт-сервисом EDT (`IExternalObjectDumper`); ИБ/учётка/версия платформы — из ассоциированного приложения проекта (`associate_infobase` обязателен), на время сборки ИБ блокируется. **Отказывается собирать при BSL-ошибках компиляции целевой обработки** (те же маркеры, что у `check_list_markers`); транзиентный «already connected» первым вызовом ретраит сам |
| `add_md_template` | `project*`, `ownerFqn*` (`<Kind>.<Name>`), `templateName*`, `synonym`, `areaName` (default = templateName), `columns` (массив ширин), `rows` (массив строк), `overwrite` (default false) |
| `rename_md_object` | `project*`, `fqn*`, `newName*` |
| `set_md_property` | `project*`, `fqn*`, `property*`, `value*`, `path` |
| `list_attributes` | `project*`, `fqn*`. Кроме `attributes`/`dimensions`/`resources` отдаёт `tabularSections` и `values` |
| `add_attribute` | `project*`, `fqn*` (owner FQN), `name*`, `type*` (строка или массив — составной тип), `role` (`Attribute`/`Dimension`/`Resource`), `synonym`, `comment` |
| `rename_attribute` | `project*`, `fqn*`, `oldName*`, `newName*`, `role` |
| `add_enum_value` | `project*`, `fqn*` (`Enum.X`), `name*`, `synonym`, `comment` |
| `borrow_md_object` | `project*`, `fqn*` |
| `borrow_form` | `project*`, `parentFqn*` (parent MdObject FQN), `formName*` |
| `borrow_form_pictures` | `project*`, `formFqn*` (**обязательно Form-FQN**: `Document.X.Form.Y` или `CommonForm.Y` — **не родитель**; `parentFqn` — deprecated-алиас) |
| `add_tabular_section` | `project*`, `ownerFqn*`, `name*` |
| `add_tabular_section_attribute` | `project*`, `tsFqn*` (`Catalog.X.TabularSection.Y`), `name*`, `type*` |
| `add_extension_method_override` | `project*`, `modulePath*`, `source*` (полный текст процедуры с аннотацией `&Перед/&После/&ИзменениеИКонтроль`) |
| `add_register_recorder` | `project*`, `register*` (`AccumulationRegister.X`), `document*` (`Document.Y`) |
| `add_subsystem_content` | `project*`, `subsystemFqn*`, `contentFqn*` |
| `set_constant_type` | `project*`, `fqn*`, `type*` |
| `set_md_type` | `project*`, `fqn*` (`Kind.Owner.Attribute/Dimension/Resource.Name`), `type*` (string или массив) |
| `create_data_composition_schema` | `project*`, `reportFqn*`, `templateName` (default `ОсновнаяСхема`) |
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

## Eventlog (2)
| Tool | Args |
|---|---|
| `get_event_log_path` | **один из** `name` / `uuid`, `srvinfoDir`, `clusterPort` (для SERVER ИБ) |
| `query_event_log` | **один из** `name` / `uuid` / `logDir`, `from`, `to`, `severity[]`, `user[]`, `userUuid[]`, `application[]`, `event[]`, `session[]`, `eventContains`, `commentContains`, `metadataContains`, `limit` (≤10000), `offset`, `order` (`date_asc`/`date_desc`, default `date_asc`), `srvinfoDir`, `clusterPort` |

> **Взаимоисключающие аргументы не видны в схеме.** Требование «ровно один из `name`/`uuid`/`logDir`»
> задано в `inputSchema` через `anyOf`, но MCP SDK такие схемы клиенту **не публикует**
> (record `JsonSchema` не имеет полей `anyOf`/`oneOf`). Поэтому оно продублировано словами
> в `description` инструмента — читай description, схема тут неполна.

`query_event_log` возвращает `partial: true`, если хвост активной `.lgp` не дочитан
(файл пишется прямо во время чтения).

## BSL модули (5)
| Tool | Args |
|---|---|
| `read_module` | `project*`, `path*` |
| `write_module` | `project*`, `path*`, `content*`, `validate` (default true). Создаёт файл (и недостающие папки), если его ещё нет |
| `get_method` | `project*`, `path*`, `name*` |
| `list_module_methods` | `project*`, `path*` |
| `get_module_info` | `project*`, `path*` |

## Forms (11)
| Tool | Args |
|---|---|
| `list_forms` | `project*`, `parentFqn` |
| `get_form` | `project*`, `fqn*` |
| `get_form_item` | `project*`, `fqn*`, `itemPath*` |
| `create_form` | `project*`, `parentFqn*`, `name*`, `formType` (`MANAGED`/`ORDINARY`, default `MANAGED` — закрытый список). Parent-kind'ы: Catalog, Document, DataProcessor, Report, InformationRegister, AccumulationRegister. Создаёт `Form.form`, пустой `Module.bsl`, `<commandInterface>` и проставляет форму основной у owner'а (kind-specific) |
| `add_form_attribute` | `project*`, `formFqn*`, `name*`, `type*`, `title`, `main` |
| `add_form_command` | `project*`, `formFqn*`, `name*`, `title`, `handlerName` |
| `add_form_field` | `project*`, `formFqn*`, `name*`, `dataPath*`, `parentPath`, `title` |
| `add_form_group` | `project*`, `formFqn*`, `name*`, `groupType*` (`Pages`/`Page`/`UsualGroup` — закрытый список, значения `Group` НЕТ), `parentPath`, `title` |
| `add_form_button` | `project*`, `formFqn*`, `name*`, `commandName*`, `parentPath`, `title` |
| `add_form_table` | `project*`, `formFqn*`, `name*`, `dataPath*`, `parentPath`, `title` |
| `set_form_handler` | `project*`, `formFqn*`, `event*`, `handlerName*`, `itemPath`. Прописывает связку в `Form.form` **и создаёт `Module.bsl`, если его нет**, дописывая stub-процедуру с правильной аннотацией и стандартной сигнатурой по event'у (идемпотент по имени процедуры). Возвращает `stubAdded` (boolean) |

## Quality (4)
| Tool | Args |
|---|---|
| `check_catalog` | `filter`, `severity`, `source` |
| `check_describe` | `checkId*` |
| `check_run` | `project*`, `path`, `checkIds`, `waitSeconds`, `clearFirst` |
| `check_list_markers` | `project*`, `path` (настоящий фильтр), `severity`, `checkId`, `source`. DTO маркера несёт реальный путь |

## Privacy — обезличивание ПДн 152-ФЗ (4)
| Tool | Args |
|---|---|
| `build_pii_catalog` | `project*` — авто-посев каталога `.mcp/pii-catalog.json` по метаданным проекта |
| `get_pii_catalog` | — |
| `set_infobase_pii_flag` | `infobase*`, `containsRealPersonalData*` (default для всех ИБ = `true`, fail-closed) |
| `get_privacy_audit` | `limit` — журнал фактов обезличивания (без самих ПДн) |

Обезличивание применяется **автоматически** к 7 инструментам, возвращающим данные ИБ:
`get_variables`, `evaluate`, `get_stack`, `query_event_log`, `run_tests`, `run_test_method`,
`set_variable`. ПДн физлиц → HMAC-псевдонимы `Физлицо#…`, спец-категории/биометрия — полное
сокрытие. Маскируются **и тексты ошибок** этих инструментов. Пока ИБ явно не помечена
`containsRealPersonalData=false`, данные маскируются.

## xUnit (8)
| Tool | Args |
|---|---|
| `create_test_module` | `project*`, `name*`, `language` |
| `add_test_method` | `project*`, `moduleFqn*`, `methodName*`, `body`. Возвращает честный `registered` + `warning`, если метод не попал в `ИсполняемыеСценарии` |
| `list_test_modules` | `project*`, `language` |
| `get_test_methods` | `project*`, `moduleFqn*` |
| `install_test_runner` | `project*` |
| `uninstall_test_runner` | `project*` |
| `run_tests` | `project*`, `infobase*`, `moduleFqn`, `user`, `password`, `timeoutSeconds`. Без установленного раннера (`install_test_runner`) — отказ сразу с подсказкой (1.20.0). `user`/`password` → `/N /P` для 1cv8 (без них — OS-аутентификация, иначе висит на диалоге логина). По таймауту процесс убивается принудительно → в результате `killed` |
| `run_test_method` | `project*`, `infobase*`, `moduleFqn*`, `methodName*`, `user`, `password`, `timeoutSeconds` |

## Client + debug (17)
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
| Составной тип | массив: `["CatalogRef.Партнеры", "CatalogRef.Контрагенты", "String(50)"]` |

`add_attribute` принимает строку или **массив строк** (составной тип). Если простой
`add_attribute` для составного не сработал — добавь атрибут с одним типом, потом
`set_md_type` массивом.
