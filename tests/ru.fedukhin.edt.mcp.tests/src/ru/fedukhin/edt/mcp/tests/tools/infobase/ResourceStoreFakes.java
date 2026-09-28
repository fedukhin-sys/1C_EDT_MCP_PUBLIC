package ru.fedukhin.edt.mcp.tests.tools.infobase;

import com._1c.g5.v8.dt.core.resource.EdtResourceMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Заменители {@code IResourceStoreManager} EDT в формате, проверенном по байткоду
 * ({@code ResourceStoreManager$2}, dt.core 27.0.2): {@code getSignaturesForExternalFiles(dir)} обходит
 * каталог и кладёт для каждого файла SHA-256 его байтов под ключом
 * {@code dir.relativize(file).toString().replace("\\", "/")}; ключи {@code getEffectiveResourceMetadata}
 * — пути от корня проекта ({@code IPath.toPortableString()}), их EDT сравнивает с первыми
 * ({@code processIncomingInfobaseResources}).
 */
public final class ResourceStoreFakes {

    private ResourceStoreFakes() {}

    /** Как {@code getSignaturesForExternalFiles(dir)}. */
    public static Map<String, byte[]> externalSignatures(Path dir) {
        Map<String, byte[]> result = new HashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(file)) {
                    result.put(dir.relativize(file).toString().replace("\\", "/"), sha256(Files.readAllBytes(file)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /**
     * Подписи ресурсов проекта глазами EDT после того, как модель обработала файлы на диске: подпись —
     * SHA-256 содержимого, UUID постоянный для ключа.
     */
    public static Map<String, EdtResourceMetadata> effective(Path projectDir) {
        Map<String, EdtResourceMetadata> result = new HashMap<>();
        externalSignatures(projectDir).forEach((key, signature) -> result.put(key,
            new EdtResourceMetadata(signature, UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)))));
        return result;
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
