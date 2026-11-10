package campus.job;

import campus.service.MediaService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResourceSweep {
    private final MediaService media;

    public ResourceSweep(MediaService media) {
        this.media = media;
    }

    @Scheduled(
            initialDelayString = "${forum.cleanup-ms:60000}",
            fixedDelayString = "${forum.cleanup-ms:60000}")
    public void sweep() {
        media.cleanup();
    }
}
