---
name: edt-mcp
description: Manage 1C:EDT extension/configuration projects via the EDT_MCP MCP server — create metadata objects, borrow from parent config, build forms, write BSL, run checks, deploy infobases. Use whenever the user wants to author or modify a 1C:EDT project from outside the IDE.
---

# EDT_MCP — пользование MCP-сервером для 1C:EDT

EDT_MCP — это MCP-плагин для 1C:EDT, экспортирующий **97 инструментов** работы с проектами 1С:Предприятие через HTTP+SSE. Этот скилл — практический справочник: реальные имена параметров, рецепты для типовых задач, главные правила работы.

Справочники: [references/tools.md](references/tools.md) — все инструменты и их аргументы;
[references/1c-gotchas.md](references/1c-gotchas.md) — грабли BSL/1С;
[references/1c-standards.md](references/1c-standards.md) — доводка объектов до стандартов 1С;
[references/smoke-harness.md](references/smoke-harness.md) — минимальный MCP-клиент для проверок.

## TL;DR — как подключиться

1. **Сервер должен крутиться в EDT IDE.** Проверь: `Get-NetTCPConnection -State Listen -LocalPort 3001`. Если нет — пользователь запускает EDT, на стартапе плагин EDT_MCP сам поднимает сервер (порт настраивается в `Window → Preferences → EDT MCP`).
2. **Токен** — в `Window → Preferences → EDT MCP` (одна строка). Сохрани в файл, скажем `MCP_token.txt`.
3. **Endpoints**: SSE = `http://127.0.0.1:3001/mcp/sse`, messages = `http://127.0.0.1:3001/mcp/messages?sessionId=…` (sessionId возвращает SSE-handshake в первом `event: endpoint`).
4. **Тестировать через готовый harness**: [references/smoke-harness.md](references/smoke-harness.md).

## Архитектурные допущения

- **Проект** — это название Eclipse-проекта в workspace, на котором открыт EDT. Например `МояКонфигурация.Расширение`. Все tools принимают `project` как строку.
- **FQN** (fully qualified name) метаданных — формат `Kind.Name`, где `Kind` ∈ `{Catalog, Document, InformationRegister, AccumulationRegister, Constant, Enum, CommonModule, Role, Subsystem, DataProcessor, Report, ChartOfCharacteristicTypes, CommonForm, …}`. Примеры: `Catalog.Партнеры`, `Document.ЗаказКлиента`. Для вложенных — `Catalog.Контрагенты.TabularSection.КонтактнаяИнформация`, `Catalog.X.Form.Y`, `Catalog.X.Attribute.Y`/`Dimension.Y`/`Resource.Y`.
- **modulePath** — относительный путь от корня проекта: `src/Catalogs/X/ObjectModule.bsl`, `src/Catalogs/X/Forms/Y/Module.bsl`, `src/CommonModules/X/Module.bsl`, `src/Documents/X/RecordSetModule.bsl` и т.п.
- **Только русский identifier set** в этом проекте (если язык конфы Russian). Имена не транслитерируются.

## Инструменты и их параметры

**Полная таблица всех 97 инструментов с точными именами аргументов и тип-выражениями —
[references/tools.md](references/tools.md).** Читай её перед первым вызовом незнакомого
инструмента: схемы строгие (`additionalProperties: false`), лишний параметр = отказ.

Группировка: workspace/projects (8), infobase+deploy (5), metadata (33), eventlog (2),
BSL-модули (5), forms (11), quality (4), privacy (4), xUnit (8), client+debug (17).

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

### Перечисление со значениями
```jsonc
[
  { "tool": "create_md_object", "args": { "project": "X", "kind": "Enum", "name": "СтатусыЗаказа" } },
  { "tool": "add_enum_value", "args": { "project": "X", "fqn": "Enum.СтатусыЗаказа", "name": "Новый", "synonym": "Новый" } },
  { "tool": "add_enum_value", "args": { "project": "X", "fqn": "Enum.СтатусыЗаказа", "name": "ВРаботе", "synonym": "В работе" } },
  // прочитать текущие значения: ключ values
  { "tool": "list_attributes", "args": { "project": "X", "fqn": "Enum.СтатусыЗаказа" } }
]
```
Ссылка на значение из BSL/типов — `EnumRef.СтатусыЗаказа`.

### Заимствование объектов основной конфы в расширение
```jsonc
[
  { "tool": "borrow_md_object", "args": { "project": "X", "fqn": "Catalog.Партнеры" } },
  { "tool": "borrow_md_object", "args": { "project": "X", "fqn": "Document.ЗаказКлиента" } },
  { "tool": "borrow_form", "args": { "project": "X", "parentFqn": "Document.ЗаказКлиента", "formName": "ФормаДокумента" } },
  { "tool": "borrow_form_pictures", "args": { "project": "X", "formFqn": "Document.ЗаказКлиента.Form.ФормаДокумента" } }
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
  { "tool": "create_form", "args": { "project": "X", "parentFqn": "Catalog.ТестСправочник", "name": "ФормаЭлемента" } },
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
  // 1. Tool привязывает имя метода в Form.form И САМ создаёт Module.bsl (если его нет)
  //    с stub-процедурой: правильная аннотация + стандартная сигнатура по event'у.
  //    Возвращает stubAdded (boolean). Идемпотентен по имени процедуры.
  { "tool": "set_form_handler", "args": {
    "project": "X",
    "formFqn": "Catalog.X.Form.ФормаЭлемента",
    "event": "OnCreateAtServer",
    "handlerName": "ПриСозданииНаСервере" } },
  // 2. Замени тело stub'а реальным кодом (write_module тоже умеет создавать файлы и папки):
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

### Внешняя обработка/отчёт + печатная форма (.mxlx)

Внешние обработки/отчёты живут в **отдельном проекте** типа `external-object` (nature `V8ExternalObjectsNature`), привязанном к родительской конфигурации. Объект добавляется **файлово** (у внешних объектов нет Configuration-контейнера, EDT API умеет лишь создавать новый проект с семенем) — это и делает `create_external_object`.

```jsonc
[
  // 0. (однократно) создать проект внешних объектов под родительскую конфу
  { "tool": "create_project", "args": { "name": "ВнешниеОбработки", "type": "external-object", "version": "8.3.27", "parentConfigurationName": "МояКонфигурация" } },
  // 1. создать саму обработку (или ExternalReport)
  { "tool": "create_external_object", "args": { "project": "ВнешниеОбработки", "kind": "ExternalDataProcessor", "name": "ДосудебнаяПретензия", "synonym": "Досудебная претензия" } },
  // 2. сгенерировать макет печатной формы .mxlx (письмо: 2 колонки, параметры, объединения)
  { "tool": "add_md_template", "args": {
      "project": "ВнешниеОбработки",
      "ownerFqn": "ExternalDataProcessor.ДосудебнаяПретензия",
      "templateName": "ПФ_MXL_ДосудебнаяПретензия",
      "synonym": "Досудебная претензия",
      "columns": [720, 224],
      "rows": [
        { "cells": [ { "parameter": "ИсходящийНомер" }, { "parameter": "БлокПолучателя" } ] },
        { "cells": [ { "text": "ДОСУДЕБНАЯ ПРЕТЕНЗИЯ", "span": 2, "bold": true, "align": "center", "wrap": false } ] },
        { "cells": [ { "parameter": "ТелоПисьма", "span": 2 } ] },
        { "cells": [ { "text": "С уважением,", "span": 2 } ] }
      ] } },
  // 3. ОБЯЗАТЕЛЬНО перед сборкой — проверка (сборка синтаксис не проверяет!)
  { "tool": "check_list_markers", "args": { "project": "ВнешниеОбработки" } },
  // 4. собрать .epf штатным экспортом EDT (сам откажется при BSL-ошибках;
  //    проекту нужна ассоциированная ИБ — associate_infobase)
  { "tool": "build_external_object", "args": {
      "project": "ВнешниеОбработки",
      "fqn": "ExternalDataProcessor.ДосудебнаяПретензия",
      "outPath": "C:/out/ДосудебнаяПретензия.epf" } }
]
```

- **`create_external_object`** пишет `src/ExternalDataProcessors/<Имя>/<Имя>.mdo` (skeleton с `producedTypes/objectType` + `containedObjects`; classId = id MdClass-а: `c3831ec8-d8d5-4f93-8a22-f9bfae07327f` обработка, `e41aff26-25cf-4bb6-b6c1-3f478a75f374` отчёт) и пустой `ObjectModule.bsl`. Логику печати (`СведенияОВнешнейОбработке`, `Печать(...)`) пишешь через `write_module`.
- **`add_md_template`** генерит `Templates/<Имя>/Template.mxlx` и регистрит `<templates>` в `.mdo`. Ячейка: `text` ИЛИ `parameter`; `span`/`rowSpan` → объединение (`<merge>`); `bold`, `size`, `align` (`left`/`center`/`right`/`justify`), `valign` (`top`/`center`/`bottom`), `wrap`. **Объединение НЕ через `<i>`** — генератор сам пишет `<merge>` (0-based `r`/`c`, `w`=span−1). `ownerFqn` поддерживает не только внешние объекты, но и `DataProcessor.X`/`Report.X`/`Catalog.X`/`Document.X`. `overwrite=false` (default) не трогает существующий макет — ручную доводку вёрстки в редакторе EDT не затрёт.
- Сумма ширин колонок должна влезать в одну печатную страницу портрета (эмпирически ≈820 ед.; 1060 → тело уходит на 2-ю страницу). В BSL печати ставь `ТабДок.АвтоМасштаб = Истина` (НЕ `РазмерБумаги = ТипРазмераБумаги.A4` — ошибка компиляции).
- **`build_external_object`** собирает `.epf`/`.erf` **штатным экспорт-сервисом EDT** (`IExternalObjectDumper` — тот же, что за «Export» в IDE), без 1cv8 DESIGNER и служебной ИБ. Аргументы: `project*`, `fqn*`, `outPath*`, `timeoutSeconds`. ИБ, учётка и версия платформы берутся из **приложения, ассоциированного с проектом** (`associate_infobase` обязателен); на время сборки ИБ блокируется. Precheck скоупится на каталог целевой обработки — BSL-блокеры соседних обработок того же проекта сборке не мешают. Первое обращение к ИБ в свежем сеансе EDT может дать транзиентный «Infobase … is already connected» — инструмент сам ретраит один раз с паузой 1 с.
- **⚠ Сборка `.epf` синтаксис НЕ проверяет** — ни экспорт EDT, ни ручной пайплайн (1cedtcli/1cv8) не компилируют модуль, битый BSL молча уедет в файл. Поэтому `build_external_object` **сам отказывается собирать**, пока у целевой обработки есть BSL-ошибки компиляции (те же маркеры, что у `check_list_markers`). Если собираешь внешним пайплайном — `check_list_markers` ДО сборки обязателен, это единственный барьер.
- «Деплой» к внешним объектам не применяется: валидация — `check_list_markers` (blocker:0/critical:0), затем `build_external_object`. Если всё же собираешь ручным пайплайном вне EDT: кириллические пути для 1cedtcli лечатся junction'ом.

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
  // user/password — если в ИБ есть пользователи (без них 1cv8 виснет на диалоге логина до таймаута)
  { "tool": "run_test_method", "args": { "project": "X", "infobase": "МояИБ", "moduleFqn": "CommonModule.МоиТесты", "methodName": "Тест_Сценарий1", "user": "Админ", "password": "..." } }
]
```

## Известное ограничение

**Extension attributes на adopted Document + form binding** — EDT-редактор формы подсвечивает «Объект.X 2 сегмент ссылается на неизвестный объект» для new attributes на adopted Document, выведенных на форму. `deploy` зелёный, расширение работает в runtime. Workaround: использовать собственный Document расширения (а не adopted) для new attributes с form binding'ом.

## Smoke harness

Минимальный JS-клиент для прогона серии tool-вызовов без Claude-клиента, endpoints,
использование и грабли (PowerShell + кавычки, node v24 + `family: 4`) —
**[references/smoke-harness.md](references/smoke-harness.md)**.

## Грабли BSL/1С из боевых задач

Полный список проверенных ловушек — **[references/1c-gotchas.md](references/1c-gotchas.md)**: зарезервированные слова (`И`, `Знач`), отсутствующие матфункции (Abs/Знак), цепочки от конструктора, `&ИзменениеИКонтроль` (запрет любых правок в теле), XDTO-порядок узлов Form.form, `\b` vs кириллица в regex, вёрстка `.mxlx`/`merge`, АвтоМасштаб/МасштабПечати, headless .epf (защита от опасных действий, `-RedirectStandardError`), операционка xUnit/MCP-сессий. Читать перед написанием BSL и ручной правкой `.mdo`/`.form`/`.mxlx`.

## Доводка объектов до стандартов 1С

MCP-инструменты создают **минимальные** объекты — для чистого прохода `check_run` их
`.mdo`/`.bsl` приходится дополнять вручную: обёртки `#Если`, области `#Область`, устаревшие
методы, представления и `Комментарий` у документов, привилегированный режим проведения,
права ролей (`setForNewObjects`), вёрстка `.mxlx`, рассинхрон модели EDT.
Всё это — **[references/1c-standards.md](references/1c-standards.md)**.

## Главные правила работы

1. **ОСНОВНУЮ КОНФИГУРАЦИЮ РАБОЧИХ БАЗ НИКОГДА НЕ МЕНЯЕМ** — любую клиентскую/продуктивную конфигурацию. Любой код, объекты, тест-раннеры, диагностика — ТОЛЬКО через расширение (extension-проект с `parentConfigurationName`) или внешнюю обработку (external-object). `install_test_runner`/`create_test_module`/`create_md_object`/`write_module` в проект-конфигурацию — запрещены; `deploy_project` проекта-конфигурации — запрещён без явного разрешения пользователя. Причина: конфигурации на замке поддержки — инкрементальная загрузка падает («редактирование объекта метаданных Configuration запрещено»), EDT молча переходит на ПОЛНУЮ загрузку (десятки минут–часы) и снимает базу с поддержки.
2. **Новое расширение → предупреди пользователя снять «Защиту от опасных действий».** При первом запуске/подключении свежего расширения платформа 1С блокирует его защитой от опасных действий (интерактивный вопрос в клиенте / отказ загрузки). Headless-запуски (тест-раннер, run_client) при этом молча виснут или падают. Перед первым запуском после `deploy_project` нового extension-проекта попроси пользователя снять защиту (Конфигуратор → расширения → снять флаг «Защита от опасных действий», или через профиль безопасности), и только после его подтверждения продолжай.
3. **Перед `deploy_project`** — обязательно `check_list_markers`, фиксь только BLOCKER (см. категоризацию выше).
4. **После любой мутации** через MCP — на критичных шагах ориентируйся на содержимое `.mdo` на диске: BM-модель обновляется асинхронно, дисковая запись — синхронная и достоверная.
5. **EDT BM async**: после `create_project`/`open_project` сразу `list_md_objects` может вернуть `namespace inactive`. Опрашивай с интервалом 5-10s до 90 секунд.
6. **1cv8 процессы**: deploy запускает 1cv8 DESIGNER который захватывает infobase. Между двумя deploys убедись что предыдущий 1cv8 завершён (`Get-Process 1cv8 | Stop-Process -Force`). Авторизуй убийство процессов у пользователя заранее.
7. **Кодировка**: EDT-файлы (`.mdo`, `.bsl`, `.form`) **только UTF-8 без BOM**. В PowerShell `Set-Content -Encoding utf8` пишет **с BOM** и double-encoding'ом, кириллица превращается в `Р`-иероглифы. Используй `[System.IO.File]::WriteAllText(path, content, [System.Text.UTF8Encoding]::new($false))` или MCP-`write_module`.
8. **CRLF vs LF**: `.env`, `.bsl` пиши с LF. На Windows `Set-Content` без `-NoNewline` ставит CRLF, что иногда ломает парсеры.
9. **Расширение метода (override)** — сигнатура процедуры **1:1** с базовой (имена параметров тоже). Иначе валидатор EDT отвергнет.
10. **Объекты регистра**: для AccumulationRegister/InformationRegister `add_attribute` обязательно указать `role: "Dimension"` или `role: "Resource"`. Без role — создастся `Attribute`, что для регистра бессмысленно.
11. **Inactive после кражи 1cv8**: если 1cv8 DESIGNER крашится посредине, EDT BM остаётся в неконсистентном состоянии. Помогает `close_project` + `open_project` + опрос BM.
12. **Расположение конфы** — для extension `parentConfigurationName` обязателен (имя родительской конфигурации). Если родителя не указать — extension не привяжется и deploy будет жаловаться на UUID-маппинг.
13. **Текст запроса — только по фактическим метаданным.** Перед написанием любого запроса (наборы данных СКД, `add_dcs_data_set_query`, `set_dcs_query_text`, запросы в BSL) сверь по `.mdo`, что все таблицы, реквизиты и поля ТЧ существуют — НЕ выдумывай реквизиты «по аналогии» и не угадывай «стандартные» имена. `check_run` ошибки запроса СКД не ловит — «Поле не найдено» всплывёт только в рантайме 1С.

## Что НЕ работает / не покрыто (по состоянию на v1.20.0)

Сверено с кодом main v1.20.0 (2026-07-17). Часть граблей прошлых редакций **закрыта** — см. «Починено в v1.18.0–v1.20.0» ниже, не воспроизводи старые workaround'ы. Оставшиеся пункты — ограничения платформы 1С/EDT, кодом плагина не закрываются.

### Актуальные ограничения (платформа 1С / EDT)

- **Сборка `.epf` синтаксис НЕ проверяет** (ограничение платформы: ни штатный экспорт EDT, ни `1cedtcli export`, ни `1cv8 DESIGNER` не компилируют BSL — битый модуль молча уедет в файл). Инструмент `build_external_object` поэтому **сам отбивает сборку при BSL-ошибках** (те же маркеры, что у `check_list_markers`), но если собираешь внешним пайплайном — `check_list_markers` ДО сборки обязателен, это единственный барьер.
- **`check_run` НЕ ловит ошибки в тексте запроса СКД** (EDT не валидирует запросы внутри `.dcs`) — «Поле не найдено» вылезет только в рантайме 1С при открытии отчёта. Сверяй запрос по `.mdo` руками (правило 13).
- **Фоновую EDT-синхронизацию нельзя отменить.** `deploy_project` по `timeoutSeconds` честно возвращает ошибку и освобождает executor, но сам EDT-пайплайн не обязан реагировать на cancel — «грязная» BM-сериализация может продолжать держать 1cv8 в фоне. Если после timeout-ошибки следующий deploy странный — кильни 1cv8 и повтори.
- **`debug_client` + breakpoints** — рабочая цепочка, но требует тёплый infobase (deploy успешен, конфа актуальна). Если не получается attach — проверь `deploy_project` сначала. Серверные точки останова этой сессией не перехватываются (см. references/1c-standards.md).
- **Extension attributes на adopted Document + form binding** — баг редактора EDT, см. «Известное ограничение» выше.
- **`CommonForm` и `CommonPicture` не создаются с нуля** — им нужны сопутствующие артефакты (`Form.form`, файл картинки), которые `create_md_object` не создаёт; доступны для чтения и `borrow_md_object`.

### Починено в v1.18.0–v1.20.0 — старые workaround'ы больше не нужны

- **`create_md_object` создаёт все data-kinds (v1.20.0):** `Task`, `BusinessProcess`, `ChartOfAccounts`, `ChartOfCalculationTypes`, `ChartOfCharacteristicTypes`, `ExchangePlan`, `DocumentJournal` больше не отбиваются.
- **`anyOf`/`oneOf` публикуются в схемах (v1.20.0):** «ровно один из `name`/`uuid`/`logDir`» у `query_event_log`/`get_event_log_path` теперь виден клиенту в `inputSchema`, не только словами в description.
- **`run_tests`/`run_test_method` без раннера падают сразу с подсказкой (v1.20.0)** — раньше висели до таймаута; `install_test_runner` по-прежнему обязателен, но забыть его теперь дёшево.

- **`deploy_project` на EDT 2026.x** (был `ClassCastException: InfobaseConflictResolutionResult cannot be cast to InfobaseConflictResolution`) — **починен**. Обход через `1cedtcli export` + `1cv8 DESIGNER /LoadCfg` больше не требуется.
- **`deploy_project` на EDT 2023.x** падал `NoSuchMethodError` на `isConnected` — **починен** (рефлексия + фолбэк). ⚠ Live-smoke на 2023.x не проводился: ожидается, но не проверено.
- **Табличные части читаются**: `list_attributes` / `get_md_object` возвращают ключ `tabularSections`.
- **Перечисления читаются и пополняются**: ключ `values` в `list_attributes` / `get_md_object` + инструмент `add_enum_value`.
- **Сборка `.epf` инструментом есть** — `build_external_object` (с обязательным precheck). С v1.19.0 — на штатном экспорт-сервисе EDT (`IExternalObjectDumper`): параметры `serviceInfobase`/`platformVersion` **удалены из схемы**, служебная ИБ и 1cv8 DESIGNER не нужны; нужна ассоциация проекта с ИБ (`associate_infobase`). С v1.19.1 precheck скоупится на целевую обработку; с v1.19.2 — авто-ретрай транзиентного «already connected».
- **`run_tests`/`run_test_method` принимают `user`/`password`** (`/N /P` для 1cv8) — запуск на ИБ с пользователями работает (подтверждено live).
- **`create_project type=configuration` работает** — создаёт скелет с `Configuration.mdo`; `get_project` видит тип, `create_md_object` не падает «namespace may not be null» (починено в v1.19.0).
- **`check_list_markers path`** стал настоящим фильтром, DTO несёт реальный путь маркера.
- **`query_event_log order=date_desc`** больше не возвращает самые старые записи; добавлен ключ `partial` для недочитанного хвоста активной `.lgp`.
- **`run_tests`**: честный таймаут (`killed` в результате), executor больше не клинит.
- **`list_md_objects`** работает на external-object проектах и знает все 19 borrow-kind'ов.

## Памятки

- Проект, в котором ты сейчас работаешь: смотри `list_projects` + `get_project name=X` (поле `exists`, `open`).
- Infobase: `list_infobases` + `get_infobase`. Имя — то, что в EDT-IDE в `Window → 1C:Enterprise → Infobases`.
- Token rotation: `Window → Preferences → EDT MCP → Regenerate token` — после ротации старый перестаёт работать, нужно обновить файл.
- Прод-сервер пишет токен в Equinox secure prefs, не в открытом виде; забирать **только** через preference page.
