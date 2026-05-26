package ru.fedukhin.edt.mcp.tools.testrun.internal;

import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.core.resources.IProject;
import ru.fedukhin.edt.mcp.core.api.ToolException;

/**
 * Редактор файла {@code <project>/src/Configuration/ManagedApplicationModule.bsl} через прямое
 * файловое I/O. Вставляет/удаляет процедуру-обработчик между маркерами
 * {@link TestRunnerInstaller#MARKER_BEGIN} / {@link TestRunnerInstaller#MARKER_END},
 * что обеспечивает идемпотентность установки/удаления без воздействия на остальной текст файла.
 */
@Singleton
public class IdeManagedAppModuleEditor implements TestRunnerInstaller.ManagedAppModuleEditor {

    private static final String MARKER_BEGIN = TestRunnerInstaller.MARKER_BEGIN;
    private static final String MARKER_END   = TestRunnerInstaller.MARKER_END;

    @Override
    public boolean hasMarker(IProject project) {
        Path file = resolveModulePath(project);
        if (!Files.exists(file)) return false;
        try {
            return Files.readString(file, StandardCharsets.UTF_8).contains(MARKER_BEGIN);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void appendConfigurationHandler(IProject project) throws ToolException {
        append(project, BslRunnerTemplates.handlerProcedureForConfiguration());
    }

    @Override
    public void appendExtensionHandler(IProject project) throws ToolException {
        append(project, BslRunnerTemplates.handlerProcedureForExtension());
    }

    @Override
    public void removeMarkerBlock(IProject project) throws ToolException {
        Path file = resolveModulePath(project);
        if (!Files.exists(file)) return;
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("can't read ManagedApplicationModule.bsl: " + e.getMessage(), e);
        }
        int start = text.indexOf(MARKER_BEGIN);
        if (start < 0) return;
        int end = text.indexOf(MARKER_END, start);
        if (end < 0) {
            throw new ToolException("found " + MARKER_BEGIN + " but no matching " + MARKER_END
                    + " in ManagedApplicationModule.bsl");
        }
        int afterEnd = end + MARKER_END.length();
        // заглатываем один завершающий перевод строки, чтобы не накапливались пустые строки
        if (afterEnd < text.length() && text.charAt(afterEnd) == '\r') afterEnd++;
        if (afterEnd < text.length() && text.charAt(afterEnd) == '\n') afterEnd++;
        String cleaned = text.substring(0, start) + text.substring(afterEnd);
        try {
            Files.writeString(file, cleaned, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("can't write ManagedApplicationModule.bsl: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------

    private void append(IProject project, String handlerBody) throws ToolException {
        Path file = resolveModulePath(project);
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new ToolException("can't create directories for ManagedApplicationModule.bsl: "
                    + e.getMessage(), e);
        }
        String existing = "";
        if (Files.exists(file)) {
            try {
                existing = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ToolException("can't read ManagedApplicationModule.bsl: " + e.getMessage(), e);
            }
        }
        if (existing.contains(MARKER_BEGIN)) {
            // уже установлено — идемпотентен
            return;
        }
        // если файл не заканчивается переводом строки, добавляем разделитель
        String separator = (existing.isEmpty()
                || existing.endsWith("\r\n")
                || existing.endsWith("\n")) ? "" : "\r\n";
        String block = separator + MARKER_BEGIN + "\r\n" + handlerBody + MARKER_END + "\r\n";
        try {
            Files.writeString(file, existing + block, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("can't write ManagedApplicationModule.bsl: " + e.getMessage(), e);
        }
    }

    private static Path resolveModulePath(IProject project) {
        return Path.of(project.getLocation().toOSString(),
                "src", "Configuration", "ManagedApplicationModule.bsl");
    }
}
