package campus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.*;
import java.util.concurrent.*;

/** Additional HTTP contract coverage for existing behavior; not artificial red-phase failures. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
class ContractCoverageTest {
    @Autowired TestRestTemplate http;
    RemainingFlowsTest api;
    MediaFlowsTest media;

    @BeforeEach
    void setup() {
        api = new RemainingFlowsTest();
        api.http = http;
        media = new MediaFlowsTest();
        media.http = http;
        media.f = api;
    }

    @Test
    void profileTransactionsAndOperationalPermissionsKeepSafeResponseFields() throws Exception {
        String user = api.user(), admin = api.admin(), other = api.user();
        var before = api.call(HttpMethod.GET, "/me", user, null).getBody();
        assertThat(before).containsOnlyKeys("id", "nickname", "role", "avatarUrl");
        var wrongPurpose = media.upload(user, "POST", media.png(), "post.png").getBody();
        var invalid =
                api.call(
                        HttpMethod.PATCH,
                        "/me",
                        user,
                        Map.of("nickname", "不得部分提交", "avatarResourceId", wrongPurpose.get("id")));
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(invalid.getBody()).containsEntry("code", "INVALID_AVATAR");
        assertThat(api.call(HttpMethod.GET, "/me", user, null).getBody()).isEqualTo(before);
        var avatar = media.upload(user, "AVATAR", media.png(), "avatar.png").getBody();
        assertThat(media.read((String) avatar.get("url"), other).getStatusCode().value())
                .isEqualTo(404);
        assertThat(media.read((String) avatar.get("url"), admin).getStatusCode().value())
                .isEqualTo(404);
        var input = Map.of("nickname", "两项一起生效", "avatarResourceId", avatar.get("id"));
        var profile = api.call(HttpMethod.PATCH, "/me", user, input).getBody();
        assertThat(profile)
                .containsEntry("nickname", "两项一起生效")
                .containsEntry("avatarUrl", avatar.get("url"));
        assertThat(api.call(HttpMethod.PATCH, "/me", user, input).getBody()).isEqualTo(profile);
        assertThat(media.read((String) avatar.get("url"), null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(api.call(HttpMethod.PATCH, "/me", admin, input).getStatusCode().value())
                .isEqualTo(403);
        assertThat(api.call(HttpMethod.GET, "/me", admin, null).getStatusCode().value())
                .isEqualTo(403);
        for (String actor : Arrays.asList(null, user)) {
            int expected = actor == null ? 401 : 403;
            assertThat(
                            api.call(HttpMethod.GET, "/admin/dependencies", actor, null)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(expected);
            assertThat(
                            api.call(HttpMethod.POST, "/admin/resources/cleanup", actor, null)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(expected);
        }
        var health = api.call(HttpMethod.GET, "/admin/dependencies", admin, null);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody())
                .containsOnlyKeys("databaseVersion", "redis", "rabbitmq")
                .containsEntry("redis", "PONG")
                .containsEntry("rabbitmq", "UP");
        var cleanup = api.call(HttpMethod.POST, "/admin/resources/cleanup", admin, null);
        assertThat(cleanup.getBody()).containsOnlyKeys("removed");
        assertThat(((Number) cleanup.getBody().get("removed")).longValue())
                .isGreaterThanOrEqualTo(0);
        var svg = http.getForEntity("/assets/default-avatar.svg", String.class);
        assertThat(svg.getStatusCode().value()).isEqualTo(200);
        assertThat(svg.getHeaders().getContentType().toString()).startsWith("image/svg+xml");
        assertThat(svg.getBody()).contains("<svg");
    }

    @Test
    void commentViewsReplyPagingAndDeletedHistoryHaveConditionalFields() throws Exception {
        String owner = api.user(), other = api.user(), admin = api.admin();
        var post = api.publish(owner, admin);
        String path = "/posts/" + post.get("id");
        var root =
                api.call(HttpMethod.POST, path + "/comments", owner, Map.of("body", "根评论"))
                        .getBody();
        String rootId = (String) root.get("id");
        assertThat(root)
                .containsOnlyKeys(
                        "id",
                        "postId",
                        "body",
                        "createdAt",
                        "rootId",
                        "replyToId",
                        "author",
                        "replyTargetDeleted",
                        "replyCount");
        assertThat(root)
                .containsEntry("rootId", null)
                .containsEntry("replyToId", null)
                .containsEntry("replyCount", 0);
        var first =
                api.call(
                                HttpMethod.POST,
                                "/comments/" + rootId + "/replies",
                                other,
                                Map.of("body", "原回复"))
                        .getBody();
        Thread.sleep(3);
        var second =
                api.call(
                                HttpMethod.POST,
                                "/comments/" + first.get("id") + "/replies",
                                owner,
                                Map.of("body", "回复对象"))
                        .getBody();
        assertThat(second)
                .containsOnlyKeys(
                        "id",
                        "postId",
                        "body",
                        "createdAt",
                        "rootId",
                        "replyToId",
                        "author",
                        "replyTargetDeleted");
        assertThat(second)
                .containsEntry("rootId", rootId)
                .containsEntry("replyToId", first.get("id"))
                .containsEntry("replyTargetDeleted", false);
        for (String order : List.of("asc", "desc")) {
            var page =
                    api.call(
                                    HttpMethod.GET,
                                    "/comments/" + rootId + "/replies?size=1&order=" + order,
                                    null,
                                    null)
                            .getBody();
            assertThat(page)
                    .containsOnlyKeys("items", "page", "size", "total")
                    .containsEntry("page", 1)
                    .containsEntry("size", 1)
                    .containsEntry("total", 2);
            assertThat(((List<Map>) page.get("items")).getFirst())
                    .containsEntry("id", order.equals("asc") ? first.get("id") : second.get("id"));
        }
        assertThat(
                        api.call(
                                        HttpMethod.GET,
                                        "/comments/" + second.get("id") + "/replies",
                                        null,
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(
                        api.call(
                                        HttpMethod.GET,
                                        "/comments/" + rootId + "/replies?size=101",
                                        null,
                                        null)
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
        api.call(HttpMethod.DELETE, "/comments/" + first.get("id"), other, null);
        var surviving =
                api.call(HttpMethod.GET, "/comments/" + second.get("id"), null, null).getBody();
        assertThat(surviving)
                .containsEntry("replyTargetDeleted", true)
                .containsEntry("replyTargetMessage", "该评论已删除")
                .containsEntry("body", "回复对象");
        var ownDeleted = api.call(HttpMethod.GET, "/comments/" + first.get("id"), other, null);
        assertThat(ownDeleted.getStatusCode().value()).isEqualTo(410);
        assertThat(ownDeleted.getBody())
                .containsOnlyKeys("code", "message")
                .containsEntry("code", "COMMENT_DELETED")
                .containsEntry("message", "该评论已删除");
        assertThat(
                        api.call(HttpMethod.GET, "/comments/" + first.get("id"), owner, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        api.call(HttpMethod.DELETE, "/comments/" + rootId, admin, null);
        var history =
                (List<Map>)
                        api.call(HttpMethod.GET, "/me/comments", other, null)
                                .getBody()
                                .get("items");
        var item =
                history.stream()
                        .filter(row -> first.get("id").equals(row.get("id")))
                        .findFirst()
                        .orElseThrow();
        assertThat(item)
                .containsOnlyKeys(
                        "id",
                        "postId",
                        "body",
                        "createdAt",
                        "rootId",
                        "replyToId",
                        "deleted",
                        "message");
        assertThat(item)
                .containsEntry("body", "原回复")
                .containsEntry("createdAt", first.get("createdAt"))
                .containsEntry("deleted", true);
        assertThat(
                        api.call(HttpMethod.GET, "/comments/" + second.get("id"), owner, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(410);
        api.call(HttpMethod.DELETE, path, owner, null);
        assertThat(
                        api.call(HttpMethod.GET, "/comments/" + rootId + "/replies", admin, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    @Test
    void successfulChunkReplacementAndDuplicateCompletePreserveRealBytes() throws Exception {
        String owner = api.user(), other = api.user(), admin = api.admin();
        byte[] base = media.video(1);
        byte[] first = Arrays.copyOf(base, base.length + 32);
        java.nio.ByteBuffer.wrap(first, base.length, 4).putInt(32);
        System.arraycopy(
                "free".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                0,
                first,
                base.length + 4,
                4);
        byte[] replacement = first.clone();
        replacement[replacement.length - 1] = 1;
        var start =
                api.call(HttpMethod.POST, "/resources/videos", owner, Map.of("size", first.length))
                        .getBody();
        String id = (String) start.get("id"), path = "/resources/" + id;
        assertThat(start)
                .containsOnlyKeys(
                        "id",
                        "type",
                        "state",
                        "size",
                        "mime",
                        "url",
                        "originalUrl",
                        "expiresAt",
                        "errorCode",
                        "duration",
                        "chunkSize",
                        "totalParts",
                        "uploadedParts");
        assertThat(start)
                .containsEntry("state", "UPLOADING")
                .containsEntry("duration", null)
                .containsEntry("errorCode", null)
                .containsEntry("uploadedParts", List.of());
        var headers = new HttpHeaders();
        headers.setBearerAuth(owner);
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        var one =
                http.exchange(
                        "/api/v1" + path + "/chunks/0",
                        HttpMethod.PUT,
                        new HttpEntity<>(first, headers),
                        Map.class);
        assertThat(one.getStatusCode().value()).isEqualTo(200);
        var duplicate =
                http.exchange(
                        "/api/v1" + path + "/chunks/0",
                        HttpMethod.PUT,
                        new HttpEntity<>(first, headers),
                        Map.class);
        assertThat(duplicate.getBody())
                .containsEntry("expiresAt", one.getBody().get("expiresAt"))
                .containsEntry("uploadedParts", List.of(0));
        assertThat(
                        http.exchange(
                                        "/api/v1" + path + "/chunks/0",
                                        HttpMethod.PUT,
                                        new HttpEntity<>(replacement, headers),
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        for (String actor : Arrays.asList(null, other, admin))
            assertThat(api.call(HttpMethod.GET, path, actor, null).getStatusCode().value())
                    .isEqualTo(404);
        assertThat(
                        api.call(HttpMethod.POST, path + "/complete", owner, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        api.call(HttpMethod.POST, path + "/complete", owner, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        Map ready = null;
        for (int i = 0; i < 120; i++) {
            ready = api.call(HttpMethod.GET, path, owner, null).getBody();
            if (!"PROCESSING".equals(ready.get("state"))) break;
            Thread.sleep(250);
        }
        assertThat(ready)
                .containsEntry("state", "READY")
                .containsEntry("uploadedParts", List.of(0))
                .containsEntry("errorCode", null);
        assertThat(api.call(HttpMethod.POST, path + "/complete", owner, null).getBody())
                .isEqualTo(ready);
        assertThat(media.read((String) ready.get("originalUrl"), owner).getBody())
                .isEqualTo(replacement);
        assertThat(
                        http.exchange(
                                        "/api/v1" + path + "/chunks/0",
                                        HttpMethod.PUT,
                                        new HttpEntity<>(replacement, headers),
                                        Map.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(409);
        assertThat(
                        media.read("/api/v1" + path + "/files/not-allowed.txt", owner)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
    }

    @Test
    void favoriteConcurrencyAndPersonalPagingReflectEachCompletedAction() throws Exception {
        String owner = api.user(), user = api.user(), admin = api.admin();
        var first = api.publish(owner, admin);
        String path = "/posts/" + first.get("id");
        try (var pool = Executors.newFixedThreadPool(8)) {
            var work = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 8; i++)
                work.add(
                        () ->
                                api.call(HttpMethod.PUT, path + "/favorites", user, null)
                                        .getStatusCode()
                                        .value());
            for (var result : pool.invokeAll(work)) assertThat(result.get()).isEqualTo(200);
        }
        assertThat(api.call(HttpMethod.GET, path, user, null).getBody())
                .containsEntry("favoriteCount", 1)
                .containsEntry("favorited", true);
        var second = api.publish(owner, admin);
        api.call(HttpMethod.PUT, "/posts/" + second.get("id") + "/favorites", user, null);
        for (int i = 0; i < 2; i++)
            assertThat(api.call(HttpMethod.DELETE, path + "/favorites", user, null).getBody())
                    .containsEntry("active", false);
        assertThat(api.call(HttpMethod.GET, path, user, null).getBody())
                .containsEntry("favoriteCount", 0)
                .containsEntry("favorited", false);
        assertThat(api.call(HttpMethod.PUT, path + "/favorites", user, null).getBody())
                .containsEntry("active", true);
        for (int page = 1; page <= 2; page++) {
            var result =
                    api.call(HttpMethod.GET, "/me/favorites?size=1&page=" + page, user, null)
                            .getBody();
            assertThat(result).containsEntry("total", 2);
            assertThat(((List<Map>) result.get("items")).getFirst())
                    .containsEntry("id", page == 1 ? first.get("id") : second.get("id"))
                    .containsEntry("favorited", true)
                    .containsEntry("liked", false);
        }
        api.call(
                HttpMethod.PUT,
                path,
                owner,
                Map.of("revision", 1, "title", "下架", "body", "正文", "resourceIds", List.of()));
        assertThat(
                        api.call(HttpMethod.PUT, path + "/favorites", user, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(api.call(HttpMethod.DELETE, path + "/favorites", user, null).getBody())
                .containsEntry("active", false);
        api.call(
                HttpMethod.POST,
                "/admin" + path + "/review",
                admin,
                Map.of("revision", 2, "decision", "APPROVE"));
        assertThat(api.call(HttpMethod.GET, path, user, null).getBody())
                .containsEntry("favoriteCount", 0)
                .containsEntry("favorited", false);
    }

    void docker(String action, String service) throws Exception {
        var process =
                new ProcessBuilder("docker", "compose", action, service)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
    }

    @Test
    void realRedisFailureReturns503AndRecoveryKeepsTheOriginalCredential() throws Exception {
        String user = api.user(), admin = api.admin();
        var profile = api.call(HttpMethod.GET, "/me", user, null).getBody();
        try {
            docker("stop", "redis");
            var protectedRead = api.call(HttpMethod.GET, "/me", user, null);
            assertThat(protectedRead.getStatusCode().value()).isEqualTo(503);
            assertThat(protectedRead.getBody()).containsEntry("code", "DEPENDENCY_UNAVAILABLE");
            assertThat(
                            api.call(HttpMethod.GET, "/admin/dependencies", admin, null)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(503);
            assertThat(api.call(HttpMethod.GET, "/posts", null, null).getStatusCode().value())
                    .isEqualTo(200);
        } finally {
            docker("start", "redis");
        }
        for (int i = 0; i < 60; i++) {
            var restored = api.call(HttpMethod.GET, "/me", user, null);
            if (restored.getStatusCode().value() == 200) {
                assertThat(restored.getBody()).isEqualTo(profile);
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Redis did not recover with the original credential");
    }

    @Test
    void hotSnapshotMetadataKeepsOriginalPositionsWhenAnItemBecomesHidden() {
        String owner = api.user(), admin = api.admin();
        var post = api.publish(owner, admin);
        String id = (String) post.get("id");
        api.call(HttpMethod.PUT, "/posts/" + id + "/favorites", owner, null);
        String snapshot = null;
        Map pageBefore = null;
        int index = 1;
        for (; index <= 100; index++) {
            var page =
                    api.call(
                                    HttpMethod.GET,
                                    "/feed/hot?size=100&page="
                                            + index
                                            + (snapshot == null ? "" : "&snapshotId=" + snapshot),
                                    null,
                                    null)
                            .getBody();
            snapshot = (String) page.get("snapshotId");
            if (((List<Map>) page.get("items"))
                    .stream().anyMatch(item -> id.equals(item.get("id")))) {
                pageBefore = page;
                break;
            }
            if (!Boolean.TRUE.equals(page.get("hasMore"))) break;
        }
        assertThat(pageBefore).isNotNull();
        assertThat(pageBefore)
                .containsOnlyKeys(
                        "snapshotId", "expiresAt", "items", "page", "size", "total", "hasMore");
        var expected =
                ((List<Map>) pageBefore.get("items"))
                        .stream()
                                .filter(item -> !id.equals(item.get("id")))
                                .map(item -> item.get("id"))
                                .toList();
        assertThat(
                        api.call(
                                        HttpMethod.PUT,
                                        "/posts/" + id,
                                        owner,
                                        Map.of(
                                                "revision",
                                                1,
                                                "title",
                                                "下架",
                                                "body",
                                                "正文",
                                                "resourceIds",
                                                List.of()))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        var after =
                api.call(
                                HttpMethod.GET,
                                "/feed/hot?size=100&page=" + index + "&snapshotId=" + snapshot,
                                null,
                                null)
                        .getBody();
        assertThat(after)
                .containsEntry("total", pageBefore.get("total"))
                .containsEntry("hasMore", pageBefore.get("hasMore"))
                .containsEntry("expiresAt", pageBefore.get("expiresAt"));
        assertThat(((List<Map>) after.get("items")).stream().map(item -> item.get("id")).toList())
                .containsExactlyElementsOf(expected);
        var empty =
                api.call(
                                HttpMethod.GET,
                                "/feed/hot?size=100&page=2147483647&snapshotId=" + snapshot,
                                null,
                                null)
                        .getBody();
        assertThat(empty)
                .containsEntry("items", List.of())
                .containsEntry("hasMore", false)
                .containsEntry("total", pageBefore.get("total"));
        assertThat(api.call(HttpMethod.GET, "/feed/hot", null, null).getBody().get("snapshotId"))
                .isNotEqualTo(snapshot);
        assertThat(api.call(HttpMethod.GET, "/feed/hot?page=2", null, null).getStatusCode().value())
                .isEqualTo(400);
        assertThat(
                        api.call(HttpMethod.GET, "/feed/hot?snapshotId=bad", null, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(410);
    }
}
