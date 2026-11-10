package campus.job;

import campus.service.VideoService;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VideoWorker {
    private final VideoService videos;

    public VideoWorker(VideoService videos) {
        this.videos = videos;
    }

    @Scheduled(
            initialDelayString = "${forum.video-poll-ms:30000}",
            fixedDelayString = "${forum.video-poll-ms:30000}")
    public void recover() {
        videos.recover();
    }

    @RabbitListener(queues = "#{@videoQueue.name}", concurrency = "1")
    public void process(String id) {
        videos.process(id);
    }
}
