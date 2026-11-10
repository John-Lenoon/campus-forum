package campus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;

import java.util.Map;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
class ContractImplementationTest {
    @Autowired TestRestTemplate http;
    RemainingFlowsTest api;

    @BeforeEach
    void setup() {
        api = new RemainingFlowsTest();
        api.http = http;
    }

    @Test
    void staleAuthorReplayCannotHideAReviewedPost() {
        String owner = api.user(), admin = api.admin();
        var first = api.publish(owner, admin);
        String path = "/posts/" + first.get("id");
        var input =
                Map.of(
                        "revision",
                        1,
                        "title",
                        "新稿",
                        "body",
                        "完整提交",
                        "resourceIds",
                        java.util.List.of());
        var edited = api.call(HttpMethod.PUT, path, owner, input);
        assertThat(edited.getStatusCode().value()).isEqualTo(200);
        assertThat(edited.getBody())
                .containsEntry("revision", 2)
                .containsEntry("status", "PENDING");
        assertThat(
                        api.call(
                                        HttpMethod.POST,
                                        "/admin" + path + "/review",
                                        admin,
                                        Map.of("revision", 2, "decision", "APPROVE"))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        var published = api.call(HttpMethod.GET, path, null, null).getBody();
        var retry = api.call(HttpMethod.PUT, path, owner, input);
        assertThat(retry.getStatusCode().value()).isEqualTo(409);
        assertThat(retry.getBody()).containsEntry("code", "STALE_REVISION");
        assertThat(api.call(HttpMethod.GET, path, null, null).getBody()).isEqualTo(published);
        assertThat(published).containsEntry("firstPublishedAt", first.get("firstPublishedAt"));
    }

    Map uploadImage(String owner) throws Exception {
        var media = new MediaFlowsTest();
        media.http = http;
        var result = media.upload(owner, "POST", media.png(), "contract.png");
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        return result.getBody();
    }

    @Test
    void omittedOrNullAttachmentsCannotClearOrHideAPublishedPost() throws Exception {
        String owner = api.user(), admin = api.admin();
        var image = uploadImage(owner);
        var post =
                api.call(
                                HttpMethod.POST,
                                "/posts",
                                owner,
                                Map.of(
                                        "title",
                                        "保留图文",
                                        "body",
                                        "正文",
                                        "resourceIds",
                                        java.util.List.of(image.get("id"))))
                        .getBody();
        String path = "/posts/" + post.get("id");
        api.call(
                HttpMethod.POST,
                "/admin" + path + "/review",
                admin,
                Map.of("revision", 1, "decision", "APPROVE"));
        var before = api.call(HttpMethod.GET, path, null, null).getBody();
        for (boolean explicitNull : new boolean[] {false, true}) {
            var input =
                    new java.util.HashMap<String, Object>(
                            Map.of("revision", 1, "title", "不得生效", "body", "正文"));
            if (explicitNull) input.put("resourceIds", null);
            var rejected = api.call(HttpMethod.PUT, path, owner, input);
            assertThat(rejected.getStatusCode().value()).isEqualTo(400);
            assertThat(rejected.getBody()).containsEntry("code", "INVALID_RESOURCES");
            assertThat(api.call(HttpMethod.GET, path, null, null).getBody()).isEqualTo(before);
            assertThat(
                            http.getForEntity((String) image.get("url"), byte[].class)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(200);
        }
    }

    @Test
    void latestAndHotUseTheSamePublicCardWithoutPersonalFlags() throws Exception {
        String owner = api.user(), admin = api.admin();
        var image = uploadImage(owner);
        var post =
                api.call(
                                HttpMethod.POST,
                                "/posts",
                                owner,
                                Map.of(
                                        "title",
                                        "统一作品卡片",
                                        "body",
                                        "详情正文",
                                        "resourceIds",
                                        java.util.List.of(image.get("id"))))
                        .getBody();
        String id = (String) post.get("id"), path = "/posts/" + id;
        api.call(
                HttpMethod.POST,
                "/admin" + path + "/review",
                admin,
                Map.of("revision", 1, "decision", "APPROVE"));
        api.call(HttpMethod.PUT, path + "/likes", owner, null);
        api.call(HttpMethod.PUT, path + "/favorites", owner, null);
        var latest = findCard("/posts", owner, id);
        var hot = findCard("/feed/hot", owner, id);
        org.assertj.core.api.SoftAssertions.assertSoftly(
                check -> {
                    check.assertThat(latest)
                            .containsOnlyKeys(
                                    "id",
                                    "title",
                                    "status",
                                    "revision",
                                    "submittedAt",
                                    "firstPublishedAt",
                                    "author",
                                    "likeCount",
                                    "favoriteCount",
                                    "attachments");
                    check.assertThat(hot)
                            .doesNotContainKeys("liked", "favorited", "body", "rejectionReason");
                    check.assertThat(hot).isEqualTo(latest);
                });
        assertThat(latest)
                .containsEntry("likeCount", 1)
                .containsEntry("favoriteCount", 1)
                .containsEntry("status", "PUBLISHED");
        var attachment = ((java.util.List<Map>) latest.get("attachments")).getFirst();
        assertThat(attachment)
                .containsOnlyKeys(
                        "id",
                        "type",
                        "state",
                        "size",
                        "mime",
                        "url",
                        "originalUrl",
                        "expiresAt",
                        "errorCode");
        assertThat(attachment)
                .containsEntry("id", image.get("id"))
                .containsEntry("state", "READY")
                .containsEntry("expiresAt", null)
                .containsEntry("errorCode", null);
        assertThat((Map) latest.get("author")).containsOnlyKeys("id", "nickname", "avatarUrl");
        assertThat(api.call(HttpMethod.GET, path, owner, null).getBody())
                .containsEntry("liked", true)
                .containsEntry("favorited", true)
                .containsEntry("body", "详情正文");
        var own =
                (java.util.List<Map>)
                        api.call(HttpMethod.GET, "/me/likes?size=100", owner, null)
                                .getBody()
                                .get("items");
        assertThat(own)
                .anySatisfy(
                        item ->
                                assertThat(item)
                                        .containsEntry("id", id)
                                        .containsEntry("liked", true));
    }

    Map findCard(String route, String user, String id) {
        String snapshot = null;
        for (int page = 1; page <= 100; page++) {
            String query =
                    route
                            + "?size=100&page="
                            + page
                            + (snapshot == null ? "" : "&snapshotId=" + snapshot);
            var response = api.call(HttpMethod.GET, query, user, null);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            var body = response.getBody();
            if (body.get("snapshotId") != null) snapshot = (String) body.get("snapshotId");
            for (var item : (java.util.List<Map>) body.get("items"))
                if (id.equals(item.get("id"))) return item;
            if (snapshot != null && !Boolean.TRUE.equals(body.get("hasMore"))) break;
        }
        throw new AssertionError("Created public post not found in " + route);
    }

    @Test
    void missingNullAndInvalidRevisionNeverChangePublishedContent() {
        String owner = api.user(), admin = api.admin();
        var post = api.publish(owner, admin);
        String path = "/posts/" + post.get("id");
        var before = api.call(HttpMethod.GET, path, null, null).getBody();
        for (int variant = 0; variant < 4; variant++) {
            var input =
                    new java.util.HashMap<String, Object>(
                            Map.of(
                                    "title",
                                    "不得修改",
                                    "body",
                                    "正文",
                                    "resourceIds",
                                    java.util.List.of()));
            if (variant == 1) input.put("revision", null);
            if (variant == 2) input.put("revision", 0);
            if (variant == 3) input.put("revision", -1);
            var result = api.call(HttpMethod.PUT, path, owner, input);
            assertThat(result.getStatusCode().value()).isEqualTo(400);
            assertThat(result.getBody()).containsEntry("code", "INVALID_REVISION");
            assertThat(api.call(HttpMethod.GET, path, null, null).getBody()).isEqualTo(before);
        }
    }

    @Test
    void sameRevisionConcurrentEditsHaveExactlyOneWinner() throws Exception {
        String owner = api.user(), admin = api.admin();
        var post = api.publish(owner, admin);
        String path = "/posts/" + post.get("id");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return api.call(
                                        HttpMethod.PUT,
                                        path,
                                        owner,
                                        Map.of(
                                                "revision",
                                                1,
                                                "title",
                                                "甲稿",
                                                "body",
                                                "正文",
                                                "resourceIds",
                                                java.util.List.of()));
                            });
            var second =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return api.call(
                                        HttpMethod.PUT,
                                        path,
                                        owner,
                                        Map.of(
                                                "revision",
                                                1,
                                                "title",
                                                "乙稿",
                                                "body",
                                                "正文",
                                                "resourceIds",
                                                java.util.List.of()));
                            });
            var a = first.get(15, java.util.concurrent.TimeUnit.SECONDS);
            var b = second.get(15, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.List.of(a.getStatusCode().value(), b.getStatusCode().value()))
                    .containsExactlyInAnyOrder(200, 409);
            var winner = a.getStatusCode().value() == 200 ? a.getBody() : b.getBody();
            var loser = a.getStatusCode().value() == 409 ? a.getBody() : b.getBody();
            assertThat(loser).containsEntry("code", "STALE_REVISION");
            var current = api.call(HttpMethod.GET, path, owner, null).getBody();
            assertThat(current)
                    .containsEntry("revision", 2)
                    .containsEntry("status", "PENDING")
                    .containsEntry("title", winner.get("title"))
                    .containsEntry("firstPublishedAt", post.get("firstPublishedAt"));
            assertThat(api.call(HttpMethod.GET, path, null, null).getStatusCode().value())
                    .isEqualTo(404);
        }
    }

    @Test
    void editingAndOldReviewRaceNeverPublishesTheNewManuscript() throws Exception {
        String owner = api.user(), admin = api.admin();
        var post = api.post(owner);
        String path = "/posts/" + post.get("id");
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var edit =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return api.call(
                                        HttpMethod.PUT,
                                        path,
                                        owner,
                                        Map.of(
                                                "revision",
                                                1,
                                                "title",
                                                "尚未审核的新稿",
                                                "body",
                                                "正文",
                                                "resourceIds",
                                                java.util.List.of()));
                            });
            var review =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return api.call(
                                        HttpMethod.POST,
                                        "/admin" + path + "/review",
                                        admin,
                                        Map.of("revision", 1, "decision", "APPROVE"));
                            });
            assertThat(edit.get(15, java.util.concurrent.TimeUnit.SECONDS).getStatusCode().value())
                    .isEqualTo(200);
            assertThat(
                            review.get(15, java.util.concurrent.TimeUnit.SECONDS)
                                    .getStatusCode()
                                    .value())
                    .isIn(200, 409);
        }
        assertThat(api.call(HttpMethod.GET, path, owner, null).getBody())
                .containsEntry("revision", 2)
                .containsEntry("status", "PENDING")
                .containsEntry("title", "尚未审核的新稿");
        assertThat(api.call(HttpMethod.GET, path, null, null).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void attachmentKeepClearAndFailedEditsAreAtomicAndKeepOwnershipRules() throws Exception {
        String owner = api.user(), other = api.user(), admin = api.admin();
        var old = uploadImage(owner);
        var fresh = uploadImage(owner);
        var post =
                api.call(
                                HttpMethod.POST,
                                "/posts",
                                owner,
                                Map.of(
                                        "title",
                                        "原稿",
                                        "body",
                                        "正文",
                                        "resourceIds",
                                        java.util.List.of(old.get("id"))))
                        .getBody();
        String path = "/posts/" + post.get("id");
        api.call(
                HttpMethod.POST,
                "/admin" + path + "/review",
                admin,
                Map.of("revision", 1, "decision", "APPROVE"));
        var original = api.call(HttpMethod.GET, path, null, null).getBody();
        var stale =
                api.call(
                        HttpMethod.PUT,
                        path,
                        owner,
                        Map.of(
                                "revision",
                                2,
                                "title",
                                "过期请求",
                                "body",
                                "正文",
                                "resourceIds",
                                java.util.List.of(fresh.get("id"))));
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(api.call(HttpMethod.GET, path, null, null).getBody()).isEqualTo(original);
        assertThat(
                        api.call(HttpMethod.GET, "/resources/" + fresh.get("id"), owner, null)
                                .getBody()
                                .get("expiresAt"))
                .isNotNull();
        var input =
                Map.of(
                        "revision",
                        1,
                        "title",
                        "保留",
                        "body",
                        "正文",
                        "resourceIds",
                        java.util.List.of(old.get("id")));
        assertThat(api.call(HttpMethod.PUT, path, other, input).getStatusCode().value())
                .isEqualTo(404);
        assertThat(api.call(HttpMethod.PUT, path, admin, input).getStatusCode().value())
                .isEqualTo(403);
        assertThat(api.call(HttpMethod.PUT, path, null, input).getStatusCode().value())
                .isEqualTo(401);
        assertThat(api.call(HttpMethod.PUT, path, owner, input).getStatusCode().value())
                .isEqualTo(200);
        api.call(
                HttpMethod.POST,
                "/admin" + path + "/review",
                admin,
                Map.of("revision", 2, "decision", "APPROVE"));
        var before = api.call(HttpMethod.GET, path, null, null).getBody();
        for (var ids :
                java.util.List.of(
                        java.util.List.of("missing"),
                        java.util.List.of(old.get("id"), old.get("id")),
                        java.util.Arrays.asList((Object) null))) {
            assertThat(
                            api.call(
                                            HttpMethod.PUT,
                                            path,
                                            owner,
                                            Map.of(
                                                    "revision",
                                                    2,
                                                    "title",
                                                    "失败修改",
                                                    "body",
                                                    "正文",
                                                    "resourceIds",
                                                    ids))
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(400);
            assertThat(api.call(HttpMethod.GET, path, null, null).getBody()).isEqualTo(before);
        }
        var cleared =
                api.call(
                        HttpMethod.PUT,
                        path,
                        owner,
                        Map.of(
                                "revision",
                                2,
                                "title",
                                "纯文字",
                                "body",
                                "正文",
                                "resourceIds",
                                java.util.List.of()));
        assertThat(cleared.getBody())
                .containsEntry("revision", 3)
                .containsEntry("attachments", java.util.List.of());
        assertThat(
                        api.call(HttpMethod.GET, "/resources/" + old.get("id"), owner, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(
                        api.call(
                                        HttpMethod.POST,
                                        "/posts",
                                        owner,
                                        Map.of(
                                                "title",
                                                "不复用",
                                                "body",
                                                "正文",
                                                "resourceIds",
                                                java.util.List.of(old.get("id"))))
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        assertThat(
                        api.call(
                                        HttpMethod.POST,
                                        "/posts",
                                        owner,
                                        Map.of(
                                                "title",
                                                "未被过期请求绑定",
                                                "body",
                                                "正文",
                                                "resourceIds",
                                                java.util.List.of(fresh.get("id"))))
                                .getStatusCode()
                                .value())
                .isEqualTo(201);
    }

    @Test
    void publicCardsSupportTextAndReadyVideoWithoutChangingDetailState() throws Exception {
        String owner = api.user(), admin = api.admin();
        var text = api.publish(owner, admin);
        assertThat(findCard("/posts", owner, (String) text.get("id")))
                .containsEntry("attachments", java.util.List.of());
        var media = new MediaFlowsTest();
        media.http = http;
        media.f = api;
        var video = media.uploadVideo(owner, media.video(1));
        assertThat(video).containsEntry("state", "READY");
        var post =
                api.call(
                                HttpMethod.POST,
                                "/posts",
                                owner,
                                Map.of(
                                        "title",
                                        "视频卡片",
                                        "body",
                                        "正文",
                                        "resourceIds",
                                        java.util.List.of(video.get("id"))))
                        .getBody();
        String path = "/posts/" + post.get("id");
        api.call(
                HttpMethod.POST,
                "/admin" + path + "/review",
                admin,
                Map.of("revision", 1, "decision", "APPROVE"));
        api.call(HttpMethod.PUT, path + "/favorites", owner, null);
        var latest = findCard("/posts", owner, (String) post.get("id"));
        var hot = findCard("/feed/hot", owner, (String) post.get("id"));
        assertThat(hot)
                .isEqualTo(latest)
                .doesNotContainKeys("liked", "favorited", "body", "rejectionReason");
        assertThat(((java.util.List<Map>) hot.get("attachments")).getFirst())
                .containsEntry("type", "VIDEO")
                .containsEntry("state", "READY")
                .containsEntry("mime", "video/mp4");
        assertThat(api.call(HttpMethod.GET, path, owner, null).getBody())
                .containsEntry("favorited", true);
    }
}
