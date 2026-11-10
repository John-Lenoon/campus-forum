package campus.infrastructure;

import static campus.exception.BusinessException.fail;

import campus.dto.VideoPart;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Streaming file operations; upload state and database transactions belong to VideoService. */
@Component
public class VideoFiles {
    private final ResourceStorage storage;
    private final MediaTools tools;

    public VideoFiles(ResourceStorage storage, MediaTools tools) {
        this.storage = storage;
        this.tools = tools;
    }

    public record ReceivedPart(Path temporary, Path chunks, String sha256, long size)
            implements AutoCloseable {
        public void retain(int index) throws IOException {
            Files.move(
                    temporary,
                    chunks.resolve(index + "-" + sha256),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public void close() {
            deleteTemporary(temporary);
        }
    }

    public ReceivedPart receive(String id, long expected, InputStream input) throws IOException {
        Path chunks = storage.directory(id).resolve("chunks");
        Files.createDirectories(chunks);
        Path temporary = Files.createTempFile(chunks, "incoming-", ".part");
        boolean received = false;
        try {
            MessageDigest digest = sha256();
            long size = 0;
            try (var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > expected) throw fail(400, "INVALID_PART_SIZE");
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                }
            }
            if (size != expected) throw fail(400, "INVALID_PART_SIZE");
            var part =
                    new ReceivedPart(
                            temporary, chunks, HexFormat.of().formatHex(digest.digest()), size);
            received = true;
            return part;
        } finally {
            if (!received) deleteTemporary(temporary);
        }
    }

    public void assemble(String id, List<VideoPart> parts, long expectedSize) throws IOException {
        Path directory = storage.directory(id);
        Path temporary = directory.resolve("original.tmp");
        long total = 0;
        try (var output = Files.newOutputStream(temporary)) {
            for (VideoPart part : parts) {
                Path source =
                        directory.resolve("chunks").resolve(part.index() + "-" + part.sha256());
                MessageDigest digest = sha256();
                long size = 0;
                try (var input = Files.newInputStream(source)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        size += count;
                    }
                }
                if (size != part.size()
                        || !part.sha256().equals(HexFormat.of().formatHex(digest.digest()))) {
                    throw fail(400, "PART_CORRUPT");
                }
                total += size;
            }
        }
        if (total != expectedSize) throw fail(400, "UPLOAD_INCOMPLETE");
        Files.move(temporary, directory.resolve("original"), StandardCopyOption.REPLACE_EXISTING);
    }

    public double createPlayback(String id, String claim) throws IOException, InterruptedException {
        Path directory = storage.directory(id);
        Path original = directory.resolve("original");
        double duration = tools.video(original);
        Path work = directory.resolve("work-" + claim);
        tools.hls(original, work);
        try (var files = Files.list(work)) {
            for (Path file :
                    files.filter(p -> !p.getFileName().toString().equals("index.m3u8")).toList()) {
                Files.move(
                        file,
                        directory.resolve(file.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // Publish the entry point last, after every segment is available.
        Files.move(
                work.resolve("index.m3u8"),
                directory.resolve("index.m3u8"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(work);
        return duration;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Unreferenced fragments remain inside the resource's eventual cleanup boundary.
        }
    }
}
