package campus;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
@org.springframework.context.annotation.Import(ContractValidationTest.FailureEndpoint.class)
class ContractValidationTest {
    @Autowired TestRestTemplate http;
    RemainingFlowsTest api;

    @BeforeEach
    void setup() {
        api = new RemainingFlowsTest();
        api.http = http;
    }

    String name() {
        return "type" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    record BadInput(HttpMethod method, String path, Object body) {}

    @org.springframework.boot.test.context.TestComponent
    @org.springframework.web.bind.annotation.RestController
    static class FailureEndpoint {
        @org.springframework.web.bind.annotation.GetMapping("/contract-fixture/unhandled")
        public void fail() {
            throw new IllegalStateException("fixture-private-detail");
        }
    }

    @Test
    void jsonScalarTypesCannotBeCoercedIntoValidBusinessValues() {
        String user = api.user();
        var post = api.post(user);
        String path = "/posts/" + post.get("id");
        var inputs =
                List.of(
                        new BadInput(
                                HttpMethod.POST,
                                "/auth/register",
                                Map.of("username", System.nanoTime(), "password", "password123")),
                        new BadInput(
                                HttpMethod.POST,
                                "/auth/register",
                                Map.of("username", name(), "password", 12345678)),
                        new BadInput(
                                HttpMethod.POST,
                                "/auth/register",
                                Map.of(
                                        "username",
                                        name(),
                                        "password",
                                        "password123",
                                        "nickname",
                                        true)),
                        new BadInput(
                                HttpMethod.POST,
                                "/auth/login",
                                Map.of("username", "admin", "password", 12345678)),
                        new BadInput(HttpMethod.POST, "/posts", Map.of("title", 123, "body", "正文")),
                        new BadInput(
                                HttpMethod.PUT,
                                path,
                                Map.of(
                                        "revision",
                                        "1",
                                        "title",
                                        "新稿",
                                        "body",
                                        "正文",
                                        "resourceIds",
                                        List.of())),
                        new BadInput(HttpMethod.POST, "/resources/videos", Map.of("size", "8")),
                        new BadInput(HttpMethod.POST, "/resources/videos", Map.of("size", 8.5)),
                        new BadInput(HttpMethod.POST, "/users/batch", Map.of("ids", List.of(123))),
                        new BadInput(
                                HttpMethod.POST,
                                "/posts",
                                Map.of("title", "正文", "body", "正文", "resourceIds", List.of(123))));
        org.assertj.core.api.SoftAssertions.assertSoftly(
                check -> {
                    for (var input : inputs) {
                        var response = api.call(input.method(), input.path(), user, input.body());
                        check.assertThat(response.getStatusCode().value())
                                .as(input.path() + " " + input.body())
                                .isEqualTo(400);
                        check.assertThat(response.getBody())
                                .containsEntry("code", "INVALID_REQUEST");
                    }
                });
        assertThat(api.call(HttpMethod.GET, path, user, null).getBody())
                .containsEntry("revision", 1)
                .containsEntry("status", "PENDING");
    }

    @Test
    void omittedOptionalFieldsDoNotMakeExplicitNullAValidUpdate() throws Exception {
        String user = api.user();
        var profile = api.call(HttpMethod.GET, "/me", user, null).getBody();
        var media = new MediaFlowsTest();
        media.http = http;
        var avatar = media.upload(user, "AVATAR", media.png(), "null-contract.png").getBody();
        var create = new java.util.HashMap<String, Object>(Map.of("title", "不得创建", "body", "正文"));
        create.put("resourceIds", null);
        var profileNullAvatar = new java.util.HashMap<String, Object>(Map.of("nickname", "不得保存"));
        profileNullAvatar.put("avatarResourceId", null);
        var profileNullName =
                new java.util.HashMap<String, Object>(Map.of("avatarResourceId", avatar.get("id")));
        profileNullName.put("nickname", null);
        org.assertj.core.api.SoftAssertions.assertSoftly(
                check -> {
                    var rejected = api.call(HttpMethod.POST, "/posts", user, create);
                    check.assertThat(rejected.getStatusCode().value()).isEqualTo(400);
                    check.assertThat(rejected.getBody()).containsEntry("code", "INVALID_RESOURCES");
                    for (var input : List.of(profileNullAvatar, profileNullName))
                        check.assertThat(
                                        api.call(HttpMethod.PATCH, "/me", user, input)
                                                .getStatusCode()
                                                .value())
                                .isEqualTo(400);
                    check.assertThat(api.call(HttpMethod.GET, "/me", user, null).getBody())
                            .isEqualTo(profile);
                });
        assertThat(
                        api.call(
                                        HttpMethod.POST,
                                        "/posts",
                                        user,
                                        Map.of("title", "允许省略", "body", "正文"))
                                .getStatusCode()
                                .value())
                .isEqualTo(201);
        assertThat(
                        api.call(HttpMethod.PATCH, "/me", user, Map.of("nickname", "合法修改"))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        api.call(
                                        HttpMethod.PATCH,
                                        "/me",
                                        user,
                                        Map.of("avatarResourceId", avatar.get("id")))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }

    @Test
    void loginPresenceErrorsAre400AndCredentialMismatchesRemain401() {
        var nullPassword = new java.util.HashMap<String, Object>(Map.of("username", "admin"));
        nullPassword.put("password", null);
        var nullName = new java.util.HashMap<String, Object>(Map.of("password", "admin123"));
        nullName.put("username", null);
        for (var input :
                List.of(
                        Map.of(),
                        Map.of("username", "admin"),
                        Map.of("password", "admin123"),
                        nullPassword,
                        nullName)) {
            var response = api.call(HttpMethod.POST, "/auth/login", null, input);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST");
        }
        var mismatch =
                api.call(
                        HttpMethod.POST,
                        "/auth/login",
                        null,
                        Map.of("username", "admin", "password", "wrong"));
        assertThat(mismatch.getStatusCode().value()).isEqualTo(401);
        assertThat(mismatch.getBody()).containsEntry("code", "INVALID_CREDENTIALS");
        assertThat(
                        api.call(
                                        HttpMethod.POST,
                                        "/auth/login",
                                        null,
                                        Map.of("username", "admin", "password", "admin123"))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }

    @Test
    void batchIdsUseUnicodeCodePointsAndRetainPrivacyAndInputOrder() {
        String user = api.user(), admin = api.admin();
        String id = (String) api.call(HttpMethod.GET, "/me", user, null).getBody().get("id");
        String hundred = "😀".repeat(100);
        var response =
                api.call(
                        HttpMethod.POST,
                        "/users/batch",
                        user,
                        Map.of("ids", List.of(id, hundred, id, "missing")));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((List) response.getBody().get("missingIds")).containsExactly(hundred, "missing");
        var items = (List<Map>) response.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsOnlyKeys("id", "nickname", "avatarUrl");
        assertThat(
                        api.call(HttpMethod.POST, "/users/batch", user, Map.of("ids", List.of()))
                                .getBody())
                .containsEntry("items", List.of())
                .containsEntry("missingIds", List.of());
        assertThat(
                        api.call(
                                        HttpMethod.POST,
                                        "/users/batch",
                                        user,
                                        Map.of("ids", java.util.Collections.nCopies(100, id)))
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        for (var invalid :
                List.of(
                        java.util.Collections.nCopies(101, id),
                        List.of(hundred + "a"),
                        List.of("\u00a0"),
                        java.util.Arrays.asList((String) null),
                        List.of("bad\u0000id"))) {
            var rejected = api.call(HttpMethod.POST, "/users/batch", user, Map.of("ids", invalid));
            assertThat(rejected.getStatusCode().value()).isEqualTo(400);
            assertThat(rejected.getBody()).containsEntry("code", "INVALID_IDS");
        }
        assertThat(api.call(HttpMethod.GET, "/users/" + id, admin, null).getBody())
                .containsOnlyKeys("id", "nickname", "avatarUrl");
        assertThat(
                        api.call(HttpMethod.POST, "/users/batch", null, Map.of("ids", List.of(id)))
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
    }

    @Test
    void transportErrorsAndUnsatisfiableRangesHaveStableSafeErrorBodies() throws Exception {
        String user = api.user();
        var media = new MediaFlowsTest();
        media.http = http;
        var avatar = media.upload(user, "AVATAR", media.png(), "range.png").getBody();
        api.call(HttpMethod.PATCH, "/me", user, Map.of("avatarResourceId", avatar.get("id")));
        var rangeHeaders = new org.springframework.http.HttpHeaders();
        rangeHeaders.set("Range", "bytes=99999999-");
        var range =
                http.exchange(
                        (String) avatar.get("url"),
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(rangeHeaders),
                        byte[].class);
        boolean jsonRange =
                org.springframework.http.MediaType.APPLICATION_JSON.isCompatibleWith(
                        range.getHeaders().getContentType());
        var rangeJson =
                !jsonRange || range.getBody() == null
                        ? null
                        : new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(range.getBody());
        var method = http.getForEntity("/api/v1/auth/login", Map.class);
        var textHeaders = new org.springframework.http.HttpHeaders();
        textHeaders.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        var unsupported =
                http.exchange(
                        "/api/v1/auth/login",
                        HttpMethod.POST,
                        new org.springframework.http.HttpEntity<>("{}", textHeaders),
                        Map.class);
        var fault = http.getForEntity("/contract-fixture/unhandled", Map.class);
        org.assertj.core.api.SoftAssertions.assertSoftly(
                check -> {
                    check.assertThat(method.getStatusCode().value()).isEqualTo(405);
                    check.assertThat(method.getBody())
                            .containsOnlyKeys("code")
                            .containsEntry("code", "METHOD_NOT_ALLOWED");
                    check.assertThat(method.getHeaders().getAllow()).contains(HttpMethod.POST);
                    check.assertThat(unsupported.getStatusCode().value()).isEqualTo(415);
                    check.assertThat(unsupported.getBody())
                            .containsOnlyKeys("code")
                            .containsEntry("code", "UNSUPPORTED_MEDIA_TYPE");
                    check.assertThat(range.getStatusCode().value()).isEqualTo(416);
                    check.assertThat(jsonRange).isTrue();
                    check.assertThat(rangeJson).isNotNull();
                    if (rangeJson != null) {
                        check.assertThat(rangeJson.size()).isEqualTo(1);
                        check.assertThat(rangeJson.path("code").asText())
                                .isEqualTo("RANGE_NOT_SATISFIABLE");
                    }
                    check.assertThat(fault.getStatusCode().value()).isEqualTo(500);
                    check.assertThat(fault.getBody())
                            .containsOnlyKeys("code")
                            .containsEntry("code", "INTERNAL_ERROR");
                });
        rangeHeaders.set("Range", "bytes=0-15");
        var partial =
                http.exchange(
                        (String) avatar.get("url"),
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(rangeHeaders),
                        byte[].class);
        assertThat(partial.getStatusCode().value()).isEqualTo(206);
        assertThat(partial.getBody()).hasSize(16);
        assertThat(partial.getHeaders().getFirst("Content-Range")).startsWith("bytes 0-15/");
    }

    @Test
    void explicitlyEmptyPagingAndSortValuesAreNotSilentlyDefaulted() {
        String user = api.user(), admin = api.admin();
        var post = api.publish(user, admin);
        org.assertj.core.api.SoftAssertions.assertSoftly(
                check -> {
                    for (String route :
                            List.of(
                                    "/posts?page=",
                                    "/posts?size=",
                                    "/feed/hot?size=",
                                    "/me/likes?size=",
                                    "/admin/posts?page=")) {
                        var response =
                                api.call(
                                        HttpMethod.GET,
                                        route,
                                        route.startsWith("/admin") ? admin : user,
                                        null);
                        check.assertThat(response.getStatusCode().value()).as(route).isEqualTo(400);
                        check.assertThat(response.getBody())
                                .containsEntry("code", "INVALID_REQUEST");
                    }
                    var sort =
                            api.call(
                                    HttpMethod.GET,
                                    "/posts/" + post.get("id") + "/comments?order=",
                                    null,
                                    null);
                    check.assertThat(sort.getStatusCode().value()).isEqualTo(400);
                    check.assertThat(sort.getBody()).containsEntry("code", "INVALID_ORDER");
                });
        assertThat(api.call(HttpMethod.GET, "/posts", null, null).getBody())
                .containsEntry("page", 1)
                .containsEntry("size", 20);
        String id = (String) api.call(HttpMethod.GET, "/me", user, null).getBody().get("id");
        assertThat(
                        api.call(HttpMethod.GET, "/users/" + id + "?page=", user, null)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
    }

    @Test
    void uploadContentTypesAreExplicitAndMissingMultipartFileRemains400() {
        String user = api.user();
        var task =
                api.call(HttpMethod.POST, "/resources/videos", user, Map.of("size", 1)).getBody();
        var headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(user);
        headers.setContentType(org.springframework.http.MediaType.TEXT_PLAIN);
        var binary =
                http.exchange(
                        "/api/v1/resources/" + task.get("id") + "/chunks/0",
                        HttpMethod.PUT,
                        new org.springframework.http.HttpEntity<>(new byte[] {1}, headers),
                        Map.class);
        var json = api.call(HttpMethod.POST, "/resources/images?purpose=POST", user, Map.of());
        org.assertj.core.api.SoftAssertions.assertSoftly(
                check -> {
                    check.assertThat(binary.getStatusCode().value()).isEqualTo(415);
                    check.assertThat(binary.getBody())
                            .containsEntry("code", "UNSUPPORTED_MEDIA_TYPE");
                    check.assertThat(json.getStatusCode().value()).isEqualTo(415);
                    check.assertThat(json.getBody())
                            .containsEntry("code", "UNSUPPORTED_MEDIA_TYPE");
                });
        headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var missing =
                http.exchange(
                        "/api/v1/resources/images?purpose=POST",
                        HttpMethod.POST,
                        new org.springframework.http.HttpEntity<>(
                                new org.springframework.util.LinkedMultiValueMap<String, Object>(),
                                headers),
                        Map.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody()).containsEntry("code", "INVALID_REQUEST");
    }

    @Test
    void jsonTypeErrorsAndParsedAttachmentCollectionErrorsAreDistinct() {
        String user = api.user(), admin = api.admin();
        var post = api.publish(user, admin);
        String path = "/posts/" + post.get("id");
        var before = api.call(HttpMethod.GET, path, null, null).getBody();
        for (Object value : List.of("not-an-array", Map.of("id", "not-an-array"), List.of(123))) {
            var response =
                    api.call(
                            HttpMethod.PUT,
                            path,
                            user,
                            Map.of(
                                    "revision",
                                    1,
                                    "title",
                                    "不得提交",
                                    "body",
                                    "正文",
                                    "resourceIds",
                                    value));
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST");
        }
        var invalids = new java.util.ArrayList<Object>();
        invalids.add(null);
        invalids.add(List.of("duplicate", "duplicate"));
        invalids.add(java.util.Arrays.asList((String) null));
        invalids.add(
                java.util.stream.IntStream.range(0, 10).mapToObj(i -> "resource" + i).toList());
        for (Object value : invalids) {
            var input =
                    new java.util.HashMap<String, Object>(
                            Map.of("revision", 1, "title", "不得提交", "body", "正文"));
            input.put("resourceIds", value);
            var response = api.call(HttpMethod.PUT, path, user, input);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).containsEntry("code", "INVALID_RESOURCES");
        }
        assertThat(api.call(HttpMethod.GET, path, null, null).getBody()).isEqualTo(before);
    }
}
