package ru.fedukhin.edt.mcp.tools.edt.workspace;

import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProjectManager;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.platform.IRuntime;
import com._1c.g5.v8.dt.platform.IRuntimeRegistry;
import com._1c.g5.v8.dt.platform.version.Version;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.fedukhin.edt.mcp.core.api.IMcpTool;
import ru.fedukhin.edt.mcp.core.api.ToolException;

public class CreateProjectTool implements IMcpTool {

    private final IWorkspace workspace;
    private final Supplier<IWorkspaceRoot> rootSupplier;
    private final IRuntimeRegistry runtimeRegistry;
    private final IConfigurationProjectManager cpm;
    private final IExtensionProjectManager epm;
    private final IExternalObjectProjectManager xpm;
    private final IV8ProjectManager projectManager;

    @Inject
    public CreateProjectTool(IRuntimeRegistry runtimeRegistry,
                             IConfigurationProjectManager cpm,
                             IExtensionProjectManager epm,
                             IExternalObjectProjectManager xpm,
                             IV8ProjectManager projectManager) {
        this(ResourcesPlugin.getWorkspace(),
             () -> ResourcesPlugin.getWorkspace().getRoot(),
             runtimeRegistry, cpm, epm, xpm, projectManager);
    }

    public CreateProjectTool(IWorkspace workspace,
                             Supplier<IWorkspaceRoot> rootSupplier,
                             IRuntimeRegistry runtimeRegistry,
                             IConfigurationProjectManager cpm,
                             IExtensionProjectManager epm,
                             IExternalObjectProjectManager xpm,
                             IV8ProjectManager projectManager) {
        this.workspace = workspace;
        this.rootSupplier = rootSupplier;
        this.runtimeRegistry = runtimeRegistry;
        this.cpm = cpm;
        this.epm = epm;
        this.xpm = xpm;
        this.projectManager = projectManager;
    }

    @Override public String name() { return "create_project"; }
    @Override public String description() { return "Create a new 1C project (configuration, extension, or external-object)"; }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> nameProp = new LinkedHashMap<>(); nameProp.put("type", "string");
        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("enum", List.of("configuration", "extension", "external-object"));
        Map<String, Object> versionProp = new LinkedHashMap<>(); versionProp.put("type", "string");
        Map<String, Object> parentProp = new LinkedHashMap<>(); parentProp.put("type", "string");
        Map<String, Object> prefixProp = new LinkedHashMap<>(); prefixProp.put("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", nameProp);
        properties.put("type", typeProp);
        properties.put("version", versionProp);
        properties.put("parentConfigurationName", parentProp);
        properties.put("namePrefix", prefixProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name", "type", "version"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override public Map<String, Object> call(Map<String, Object> args) throws ToolException {
        String name = GetProjectTool.stringArg(args, "name");
        String type = GetProjectTool.stringArg(args, "type");
        String versionStr = GetProjectTool.stringArg(args, "version");
        String parentName = args == null ? null : (String) args.get("parentConfigurationName");
        String namePrefix = args == null ? null : (String) args.get("namePrefix");

        IWorkspaceRoot root = rootSupplier.get();
        if (root.getProject(name).exists()) {
            throw new ToolException("project '" + name + "' already exists");
        }

        Version version = findRuntimeVersion(versionStr);
        if (version == null) {
            throw new ToolException("unsupported runtime version: " + versionStr + "; supported: " + listVersions());
        }

        IProject parent = null;
        if ("extension".equals(type) || "external-object".equals(type)) {
            if (parentName == null || parentName.isEmpty()) {
                throw new ToolException("'" + type + "' project requires 'parentConfigurationName'");
            }
            parent = root.getProject(parentName);
            if (!parent.exists()) {
                throw new ToolException("parent project '" + parentName + "' not found");
            }
            if (!parent.isOpen()) {
                throw new ToolException("parent project '" + parentName + "' is closed");
            }
        }

        final IProject parentFinal = parent;
        final Version versionFinal = version;
        final IProject[] createdHolder = new IProject[1];

        try {
            workspace.run((IWorkspaceRunnable) monitor -> {
                switch (type) {
                    case "configuration":
                        createdHolder[0] = cpm.create(name, versionFinal, null, new NullProgressMonitor());
                        break;
                    case "extension":
                        createdHolder[0] = epm.create(name, versionFinal, null, parentFinal, new NullProgressMonitor());
                        break;
                    case "external-object":
                        createdHolder[0] = xpm.create(name, versionFinal, (MdObject) null, parentFinal, new NullProgressMonitor());
                        break;
                    default:
                        throw new CoreException(org.eclipse.core.runtime.Status.error("unsupported type: " + type));
                }
            }, new NullProgressMonitor());
        } catch (CoreException e) {
            throw new ToolException("create failed: " + e.getMessage());
        }

        IProject created = createdHolder[0];

        // BUG-01/BUG-11 fix: IExtensionProjectManager.create() leaves an extension
        // project without src/Configuration/Configuration.mdo — the BM namespace
        // never activates and deploy later fails with a missing-InternalInfo error.
        boolean configWritten = false;
        String configWarning = null;
        if ("extension".equals(type)) {
            configWarning = writeExtensionConfiguration(created, parentFinal,
                    configurationName(name), namePrefix, String.valueOf(versionFinal));
            configWritten = true;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", created.getName());
        out.put("type", type);
        out.put("version", String.valueOf(versionFinal));
        out.put("location", created.getLocation() == null ? null : created.getLocation().toString());
        if (configWritten) {
            out.put("configurationMdoWritten", true);
            out.put("note", "Configuration.mdo written and project reloaded; "
                    + "the BM namespace may take a few seconds to activate — "
                    + "poll list_md_objects until it succeeds.");
            if (configWarning != null) {
                out.put("warning", configWarning);
            }
        }
        return out;
    }

    /** 1C configuration name (no dots) derived from the project name. */
    private static String configurationName(String projectName) {
        int dot = projectName.lastIndexOf('.');
        return dot >= 0 && dot < projectName.length() - 1
                ? projectName.substring(dot + 1)
                : projectName;
    }

    /**
     * Writes a complete extension {@code src/Configuration/Configuration.mdo}
     * (including the {@code <containedObjects>} block required so deploy does not
     * fail with «Отсутствует InternalInfo») and reloads the project so EDT parses
     * it into the BM model. No-op if the file already exists.
     *
     * <p>The adopted {@code <languages>} element must reference the parent
     * configuration's language by its real {@code uuid} via
     * {@code extendedConfigurationObject} — otherwise deploy fails with
     * «Значение контролируемого свойства ОбъектРасширяемойКонфигурации … не
     * совпадает». The uuid/name/languageCode are read from the parent's
     * {@code Configuration.mdo}.
     *
     * @return a warning message when the parent language could not be resolved
     *         (a placeholder uuid was used), otherwise {@code null}
     */
    private String writeExtensionConfiguration(IProject project, IProject parent,
                                               String configName, String namePrefix,
                                               String version) throws ToolException {
        IFile mdo = project.getFile("src/Configuration/Configuration.mdo");
        if (mdo.exists()) {
            return null;
        }

        ParentLanguage lang = resolveParentLanguage(parent);
        String warning = null;
        String langUuid;
        String langName;
        String langCode;
        if (lang != null) {
            langUuid = lang.uuid;
            langName = lang.name;
            langCode = lang.code;
        } else {
            langUuid = UUID.randomUUID().toString();
            langName = "Русский";
            langCode = "ru";
            warning = "could not resolve the parent configuration language from its "
                    + "Configuration.mdo; <languages extendedConfigurationObject> was "
                    + "filled with a placeholder uuid — deploy will fail until you set "
                    + "it to the parent language's real uuid.";
        }

        // Fixed mdclass classIds for the 7 contained-object slots an extension
        // Configuration must declare; objectIds are freshly generated.
        String[] classIds = {
            "9cd510cd-abfc-11d4-9434-004095e12fc7",
            "9fcd25a0-4822-11d4-9414-008048da11f9",
            "e3687481-0a87-462c-a166-9f34594f9bba",
            "9de14907-ec23-4a07-96f0-85521cb6b53b",
            "51f2d5d8-ea4d-4064-8892-82951750031e",
            "e68182ea-4237-4383-967f-90c1e3370bc7",
            "fb282519-d103-4dd3-bc12-cb271d631dfc"
        };
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<mdclass:Configuration xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
          .append(" xmlns:mdclass=\"http://g5.1c.ru/v8/dt/metadata/mdclass\"")
          .append(" xmlns:mdclassExtension=\"http://g5.1c.ru/v8/dt/metadata/mdclass/extension\"")
          .append(" uuid=\"").append(UUID.randomUUID()).append("\">\n");
        sb.append("  <name>").append(configName).append("</name>\n");
        sb.append("  <synonym>\n    <key>ru</key>\n    <value>").append(configName)
          .append("</value>\n  </synonym>\n");
        sb.append("  <objectBelonging>Adopted</objectBelonging>\n");
        sb.append("  <extension xsi:type=\"mdclassExtension:ConfigurationExtension\">\n");
        sb.append("    <defaultRunMode>Checked</defaultRunMode>\n");
        sb.append("    <usePurposes>Checked</usePurposes>\n");
        sb.append("    <defaultLanguage>Checked</defaultLanguage>\n");
        sb.append("  </extension>\n");
        for (String classId : classIds) {
            sb.append("  <containedObjects classId=\"").append(classId)
              .append("\" objectId=\"").append(UUID.randomUUID()).append("\"/>\n");
        }
        sb.append("  <keepMappingToExtendedConfigurationObjectsByIDs>true")
          .append("</keepMappingToExtendedConfigurationObjectsByIDs>\n");
        if (namePrefix != null && !namePrefix.isEmpty()) {
            sb.append("  <namePrefix>").append(namePrefix).append("</namePrefix>\n");
        }
        sb.append("  <configurationExtensionCompatibilityMode>").append(version)
          .append("</configurationExtensionCompatibilityMode>\n");
        sb.append("  <configurationExtensionPurpose>Customization</configurationExtensionPurpose>\n");
        sb.append("  <defaultRunMode>ManagedApplication</defaultRunMode>\n");
        sb.append("  <usePurposes>PersonalComputer</usePurposes>\n");
        sb.append("  <scriptVariant>Russian</scriptVariant>\n");
        sb.append("  <defaultLanguage>Language.").append(langName).append("</defaultLanguage>\n");
        sb.append("  <languages uuid=\"").append(UUID.randomUUID())
          .append("\" extendedConfigurationObject=\"").append(langUuid).append("\">\n");
        sb.append("    <name>").append(langName).append("</name>\n");
        sb.append("    <objectBelonging>Adopted</objectBelonging>\n");
        sb.append("    <extension xsi:type=\"mdclassExtension:LanguageExtension\">\n");
        sb.append("      <extendedConfigurationObject>Checked</extendedConfigurationObject>\n");
        sb.append("      <languageCode>Checked</languageCode>\n");
        sb.append("    </extension>\n");
        sb.append("    <languageCode>").append(langCode).append("</languageCode>\n");
        sb.append("  </languages>\n");
        sb.append("</mdclass:Configuration>\n");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        try {
            workspace.run((IWorkspaceRunnable) monitor -> {
                IFolder src = project.getFolder("src");
                if (!src.exists()) {
                    src.create(true, true, monitor);
                }
                IFolder cfgDir = project.getFolder("src/Configuration");
                if (!cfgDir.exists()) {
                    cfgDir.create(true, true, monitor);
                }
                mdo.create(new ByteArrayInputStream(bytes), true, monitor);
            }, new NullProgressMonitor());
            // Reload so EDT parses the new Configuration.mdo into the BM model.
            project.close(new NullProgressMonitor());
            project.open(new NullProgressMonitor());
        } catch (CoreException e) {
            throw new ToolException("extension project created, but writing "
                    + "Configuration.mdo failed: " + e.getMessage());
        }
        return warning;
    }

    /** Parent configuration language coordinates needed for an adopted extension language. */
    private static final class ParentLanguage {
        final String uuid;
        final String name;
        final String code;
        ParentLanguage(String uuid, String name, String code) {
            this.uuid = uuid;
            this.name = name;
            this.code = code;
        }
    }

    /**
     * Reads the parent configuration's primary {@code <languages>} element from its
     * {@code src/Configuration/Configuration.mdo}. Returns {@code null} when the file
     * is missing or cannot be parsed.
     */
    private ParentLanguage resolveParentLanguage(IProject parent) {
        if (parent == null) {
            return null;
        }
        IFile cfg = parent.getFile("src/Configuration/Configuration.mdo");
        try {
            if (!cfg.exists()) {
                return null;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignore) {
                // feature unsupported by this parser — the parsed file is trusted project metadata
            }
            Document doc;
            try (InputStream in = cfg.getContents()) {
                doc = factory.newDocumentBuilder().parse(in);
            }
            NodeList langs = doc.getElementsByTagName("languages");
            if (langs.getLength() == 0) {
                return null;
            }
            Element lang = (Element) langs.item(0);
            String uuid = lang.getAttribute("uuid");
            if (uuid == null || uuid.isEmpty()) {
                return null;
            }
            String name = childText(lang, "name");
            String code = childText(lang, "languageCode");
            return new ParentLanguage(uuid,
                    name == null || name.isEmpty() ? "Русский" : name,
                    code == null || code.isEmpty() ? "ru" : code);
        } catch (Exception e) {
            return null;
        }
    }

    /** Text of the first direct child element {@code <tag>} of {@code parent}, or {@code null}. */
    private static String childText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getParentNode() == parent) {
                String text = n.getTextContent();
                return text == null ? null : text.trim();
            }
        }
        return null;
    }

    private Version findRuntimeVersion(String s) {
        for (IRuntime r : runtimeRegistry.getRuntimes()) {
            if (String.valueOf(r.getVersion()).equals(s)) {
                return r.getVersion();
            }
        }
        return null;
    }

    private List<String> listVersions() {
        List<String> out = new ArrayList<>();
        for (IRuntime r : runtimeRegistry.getRuntimes()) {
            out.add(String.valueOf(r.getVersion()));
        }
        return out;
    }
}
