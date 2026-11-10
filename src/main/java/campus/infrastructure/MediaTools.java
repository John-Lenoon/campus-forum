package campus.infrastructure;

import static campus.exception.BusinessException.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class MediaTools {
    private final ObjectMapper json;
    final String ffmpeg;
    final String ffprobe;

    MediaTools(ObjectMapper json, @Value("${forum.ffmpeg:}") String configured) {
        this.json = json;
        String executable = configured;
        if (executable.isBlank() && Files.isDirectory(Path.of(".tools"))) {
            try (var paths = Files.walk(Path.of(".tools"), 4)) {
                executable =
                        paths.filter(p -> p.getFileName().toString().equals("ffmpeg.exe"))
                                .findFirst()
                                .map(p -> p.toAbsolutePath().toString())
                                .orElse("");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        ffmpeg = executable.isBlank() ? "ffmpeg" : executable;
        ffprobe =
                ffmpeg.endsWith("ffmpeg.exe")
                        ? ffmpeg.substring(0, ffmpeg.length() - 10) + "ffprobe.exe"
                        : ffmpeg.endsWith("/ffmpeg")
                                ? ffmpeg.substring(0, ffmpeg.length() - 6) + "ffprobe"
                                : "ffprobe";
    }

    public JsonNode probe(Path path, boolean frames) {
        var args = new ArrayList<>(List.of(ffprobe, "-v", "error"));
        if (frames) args.add("-count_frames");
        args.addAll(
                List.of(
                        "-protocol_whitelist",
                        "file,pipe",
                        "-show_entries",
                        "stream=codec_name,codec_type,nb_read_frames:format=duration,format_name:format_tags=major_brand",
                        "-of",
                        "json",
                        path.toString()));
        try {
            var process =
                    new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw fail(400, "INVALID_MEDIA");
            }
            byte[] output = process.getInputStream().readNBytes(65536);
            if (process.exitValue() != 0) throw fail(400, "INVALID_MEDIA");
            return json.readTree(output);
        } catch (IOException e) {
            throw fail(503, "MEDIA_TOOL_UNAVAILABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw fail(503, "MEDIA_INTERRUPTED");
        }
    }

    public String image(Path path, boolean avatar) {
        var probe = probe(path, true);
        String format = probe.path("format").path("format_name").asText();
        if (!Set.of("png_pipe", "jpeg_pipe", "webp_pipe", "webp", "webp_anim", "apng", "image2")
                .contains(format)) throw fail(400, "INVALID_IMAGE");
        var streams = probe.path("streams");
        if (!streams.isArray() || streams.isEmpty()) throw fail(400, "INVALID_IMAGE");
        var stream = streams.get(0);
        String codec = stream.path("codec_name").asText();
        if (!Set.of("png", "mjpeg", "webp", "webp_anim", "apng").contains(codec))
            throw fail(400, "INVALID_IMAGE");
        if (avatar) {
            if (codec.equals("apng")
                    || codec.equals("webp_anim")
                    || stream.path("nb_read_frames").asInt(1) > 1)
                throw fail(400, "ANIMATED_AVATAR");
            try (var in = Files.newInputStream(path)) {
                var head = in.readNBytes(32);
                if (codec.equals("webp")
                        && head.length > 20
                        && new String(head, 12, 4, java.nio.charset.StandardCharsets.US_ASCII)
                                .equals("VP8X")
                        && (head[20] & 2) != 0) throw fail(400, "ANIMATED_AVATAR");
            } catch (IOException e) {
                throw fail(503, "STORAGE_UNAVAILABLE");
            }
        }
        return codec.equals("mjpeg")
                ? "image/jpeg"
                : codec.startsWith("webp") ? "image/webp" : "image/png";
    }

    public double video(Path path) {
        var data = probe(path, false);
        boolean video = false;
        for (var stream : data.path("streams"))
            if ("video".equals(stream.path("codec_type").asText())) video = true;
        String format = data.path("format").path("format_name").asText();
        String brand =
                data.path("format")
                        .path("tags")
                        .path("major_brand")
                        .asText()
                        .trim()
                        .toLowerCase(Locale.ROOT);
        if (!video
                || !format.contains("mp4")
                || brand.equals("qt")
                || brand.startsWith("3gp")
                || brand.startsWith("3g2")) throw fail(400, "INVALID_MP4");
        double duration = data.path("format").path("duration").asDouble(-1);
        if (!Double.isFinite(duration) || duration <= 0 || duration > 1800)
            throw fail(400, "VIDEO_DURATION_LIMIT");
        return duration;
    }

    public void hls(Path source, Path destination) throws IOException, InterruptedException {
        Files.createDirectories(destination);
        var command =
                List.of(
                        ffmpeg,
                        "-nostdin",
                        "-v",
                        "error",
                        "-y",
                        "-protocol_whitelist",
                        "file,pipe",
                        "-i",
                        source.toString(),
                        "-map",
                        "0:v:0",
                        "-map",
                        "0:a?",
                        "-vf",
                        "scale=ceil(iw/2)*2:ceil(ih/2)*2",
                        "-c:v",
                        "libx264",
                        "-preset",
                        "veryfast",
                        "-pix_fmt",
                        "yuv420p",
                        "-c:a",
                        "aac",
                        "-force_key_frames",
                        "expr:gte(t,n_forced*6)",
                        "-f",
                        "hls",
                        "-hls_time",
                        "6",
                        "-hls_list_size",
                        "0",
                        "-hls_playlist_type",
                        "vod",
                        "-hls_segment_filename",
                        destination.resolve("part%05d.ts").toString(),
                        destination.resolve("index.m3u8").toString());
        var process =
                new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        if (!process.waitFor(2, TimeUnit.HOURS)) {
            process.destroyForcibly();
            throw fail(400, "PROCESSING_TIMEOUT");
        }
        if (process.exitValue() != 0) throw fail(400, "PROCESSING_FAILED");
        var manifest = Files.readString(destination.resolve("index.m3u8"));
        if (!manifest.contains("#EXT-X-ENDLIST")) throw fail(400, "PROCESSING_FAILED");
        int segments = 0;
        for (String line : manifest.lines().toList())
            if (!line.isBlank() && !line.startsWith("#")) {
                if (!line.matches("part[0-9]+\\.ts")
                        || !Files.isRegularFile(destination.resolve(line))
                        || Files.size(destination.resolve(line)) == 0)
                    throw fail(400, "PROCESSING_FAILED");
                segments++;
            }
        if (segments == 0) throw fail(400, "PROCESSING_FAILED");
    }
}
