# Contributing — EDT_MCP

## Требования к окружению

- JDK 17.
- Maven 3.9+ (бинарь `mvn` не обязан быть в PATH; пример пути: `E:\Tools\maven\apache-maven-3.9.9\bin\mvn.cmd`).
- Локально установленный 1C:EDT 2026.1; target platform тащится из его p2-пула (`C:/Users/User/.p2/pool/plugins`) — см. `targets/default/default.target`.

## Сборка

```
mvn clean verify
```

Артефакты:
- p2 update site: `repositories/ru.fedukhin.edt.mcp.repository/target/repository/`.
- Тестов: 685 (unit + integration с полным MCP SSE handshake; 10 `@Ignore` — Jackson LinkageError под tycho-surefire и headless-xtext ограничения, см. javadoc на самих классах).

## Структура

11 bundles + feature + p2 repo + target platform:

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
- `bundles/ru.fedukhin.edt.mcp.ui` — `AbstractUIPlugin`, preference page, команды, status-bar.

Tools регистрируются через extension point `ru.fedukhin.edt.mcp.core.tool` — пример в `bundles/ru.fedukhin.edt.mcp.tools.edt/plugin.xml`.

## Implementation notes

- MCP SDK: `io.modelcontextprotocol.sdk:mcp-core:1.1.2` (+ `mcp-json-jackson2`).
  `McpJsonMapper` и `JsonSchemaValidator` передаются явно — SDK ServiceLoader / OSGi DS-путь нестабилен под tycho-surefire.
- Target platform — `<location type="Directory">` на локальный 1C:EDT 2026.1 pool (онлайн p2 InstallableUnit-режим не разруливает EDT + Eclipse 2023-12 + Xtext set).
- Тесты используют in-memory token storage (`mcp.security.useInMemory=true`, см. parent pom tycho-surefire `<systemProperties>`); production — Equinox secure preferences.

## Релизный процесс

1. Все доки/код приведены в соответствие.
2. `mvn verify` → BUILD SUCCESS + 685 PASS.
3. PR в `main` с описанием изменений.
4. После merge — `git tag vX.Y.Z` + `git push origin vX.Y.Z`.
5. `gh release create vX.Y.Z` (опционально с release notes).
6. Sync публичного зеркала `1C_EDT_MCP_PUBLIC` (snapshot-коммит).

## Bumping версии

Версия живёт в двух местах, должна быть согласована (иначе `tycho-packaging-plugin:validate-version` падает):
- `features/ru.fedukhin.edt.mcp.feature/feature.xml` → `version="X.Y.Z.qualifier"`.
- `features/ru.fedukhin.edt.mcp.feature/pom.xml` → `<version>X.Y.Z-SNAPSHOT</version>`.

Bundle-Version в `META-INF/MANIFEST.MF` каждого bundle'а — это отдельный жизненный цикл, обычно не меняется.

## Известные ограничения

- 7 integration-тестов `@Ignore` из-за Jackson LinkageError под tycho-surefire (Jackson 2.20 внутри `core` bundle ↔ EDT runtime classloader). Lehmgen — отдельный stage.
- `Stage3cDebugLaunchProbeTest`, `InfobaseRegistryIntegrationTest`, `ClientLauncherIntegrationTest` — требуют живой IDE / 1С runtime, manual smoke only.
- `add_use_as_is_reference` отвергнут как broken (revert `b9811b1`); use-as-is CommonForm ⇒ обязательный `borrow_md_object` flow (full inline-borrow).
- `extend_form_attribute_type` отменён 2026-05-19 (нет канонического образца для reverse-engineering, deploy=зелёный без него).
