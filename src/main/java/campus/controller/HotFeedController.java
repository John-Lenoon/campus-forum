package campus.controller;

import campus.service.HotFeedService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HotFeedController {
    private final HotFeedService feed;

    public HotFeedController(HotFeedService feed) {
        this.feed = feed;
    }

    @GetMapping("/api/v1/feed/hot")
    public Map<String, Object> list(
            @RequestParam(required = false) String snapshotId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return feed.list(snapshotId, page, size);
    }
}
