package ru.fedukhin.edt.mcp.tests.tools.infobase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.fedukhin.edt.mcp.tools.infobase.internal.OutLogReader;

public class OutLogReaderTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path write(byte[] bytes) throws IOException {
        Path p = tmp.newFile().toPath();
        Files.write(p, bytes);
        return p;
    }

    @Test
    public void utf8WithBom_isDecodedWithoutBom() throws Exception {
        byte[] text = "Ошибка СУБД".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[text.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(text, 0, withBom, 3, text.length);

        assertEquals("Ошибка СУБД", OutLogReader.read(write(withBom)));
    }

    @Test
    public void utf8WithoutBom_isDecoded() throws Exception {
        assertEquals("Ошибка СУБД", OutLogReader.read(write("Ошибка СУБД\r\n".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void windows1251_isDetected() throws Exception {
        byte[] ansi = "Ошибка СУБД".getBytes(Charset.forName("windows-1251"));
        assertEquals("Ошибка СУБД", OutLogReader.read(write(ansi)));
    }

    @Test
    public void missingFile_isEmpty() {
        assertEquals("", OutLogReader.read(tmp.getRoot().toPath().resolve("нет-такого.log")));
    }

    @Test
    public void longLog_isTruncated() throws Exception {
        String text = OutLogReader.read(write("x".repeat(5000).getBytes(StandardCharsets.UTF_8)));
        assertEquals(4001, text.length());
        assertTrue(text.endsWith("…"));
    }
}
