package campus.infrastructure;

import static campus.exception.BusinessException.fail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Private resource storage. Callers must authorize access before returning any path. */
@Component
public class ResourceStorage {
    private final Path root;

    public ResourceStorage(@Value("${forum.storage-root:data/resources}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Storage directory unavailable", e);
        }
    }

    public Path directory(String id) {
        if (!id.matches("[a-f0-9-]{36}")) throw fail(404, "NOT_FOUND");
        Path path = root.resolve(id).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw fail(404, "NOT_FOUND");
        return path;
    }

    public Path storeImage(String id, MultipartFile file) throws IOException {
        Path directory = directory(id);
        Files.createDirectories(directory);
        Path original = directory.resolve("original");
        file.transferTo(original);
        return original;
    }

    public Path existingFile(String id, String filename) {
        Path path = directory(id).resolve(filename);
        if (!Files.isRegularFile(path)) throw fail(404, "NOT_FOUND");
        return path;
    }

    public void removeDirectory(String id) {
        Path directory = directory(id);
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw fail(503, "STORAGE_UNAVAILABLE");
        }
    }
}
