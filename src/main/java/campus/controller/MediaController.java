package campus.controller;

import static campus.exception.BusinessException.fail;

import campus.dto.VideoStartRequest;
import campus.service.MediaService;
import campus.service.SessionService;
import campus.service.VideoService;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/resources")
public class MediaController {
    private final SessionService sessions;
    private final MediaService media;
    private final VideoService jobs;

    MediaController(SessionService sessions, MediaService media, VideoService jobs) {
        this.sessions = sessions;
        this.media = media;
        this.jobs = jobs;
    }

    @PostMapping("/videos")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> start(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody VideoStartRequest input) {
        return jobs.start(sessions.require(auth, "USER"), input.size());
    }

    @PutMapping(value = "/{id}/chunks/{index}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    Map<String, Object> chunk(
            @PathVariable String id,
            @PathVariable int index,
            @RequestHeader(value = "Authorization", required = false) String auth,
            jakarta.servlet.http.HttpServletRequest request)
            throws java.io.IOException {
        return jobs.chunk(id, index, sessions.require(auth, "USER"), request.getInputStream());
    }

    @PostMapping("/{id}/complete")
    Map<String, Object> complete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return jobs.complete(id, sessions.require(auth, "USER"));
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> image(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam String purpose,
            @RequestParam MultipartFile file) {
        return media.image(sessions.require(auth, "USER"), purpose, file);
    }

    @GetMapping("/{id}")
    Map<String, Object> info(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return media.info(id, sessions.read(auth, false));
    }

    @GetMapping("/{id}/files/{filename}")
    ResponseEntity<Resource> file(
            @PathVariable String id,
            @PathVariable String filename,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "Range", required = false) String range) {
        var file = media.download(id, filename, sessions.read(auth, false));
        var resource = new FileSystemResource(file.path());
        if (range != null) {
            try {
                HttpRange.toResourceRegions(HttpRange.parseRanges(range), resource);
            } catch (IllegalArgumentException e) {
                throw fail(416, "RANGE_NOT_SATISFIABLE");
            }
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(file.mime()))
                .body(resource);
    }
}
