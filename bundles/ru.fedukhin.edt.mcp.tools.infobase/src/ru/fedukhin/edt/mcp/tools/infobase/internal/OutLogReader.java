package ru.fedukhin.edt.mcp.tools.infobase.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Читает лог {@code /Out} пакетного 1cv8.
 *
 * <p>1cv8 пишет туда причину отказа (в stderr — пусто), но кодировку файла не гарантирует:
 * встречается и UTF-8 с BOM, и системная windows-1251. Порядок: BOM → UTF-8; строгое
 * декодирование как UTF-8 удалось → UTF-8; иначе → windows-1251.
 */
public final class OutLogReader {

    static final int MAX_CHARS = 4000;

    private OutLogReader() {}

    /** @return текст лога без BOM и крайних пробелов; пустая строка, если файла нет или он нечитаем */
    public static String read(Path file) {
        if (file == null || !Files.isRegularFile(file)) return "";
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return "";
        }
        String text = decode(bytes).strip();
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) + "…" : text;
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1251"));
        }
    }
}
