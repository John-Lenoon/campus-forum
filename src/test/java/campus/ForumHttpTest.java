package campus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import java.time.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
@Import(ForumHttpTest.TimeConfig.class)
class ForumHttpTest {
    @Autowired TestRestTemplate http;
    @Autowired TestClock clock;
    static class TestClock extends Clock {
        volatile long offset;
        volatile Long fixed;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return fixed==null?Instant.now().plusMillis(offset):Instant.ofEpochMilli(fixed); }
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }
    @BeforeEach void resetTime() { clock.offset=0;clock.fixed=null; }

    @Test void equalPublicationTimesUseIdOrderAndApproveRetryKeepsFirstTime() {
        clock.fixed=System.currentTimeMillis();String token=registerUser(),adm=admin();
        var a=post(token);var b=post(token);
        for(var p:java.util.List.of(a,b)) {
            var result=request(HttpMethod.POST,"/api/v1/admin/posts/"+p.get("id")+"/review",adm,Map.of("revision",1,"decision","APPROVE"));
            assertThat(result.getStatusCode().value()).isEqualTo(200);
        }
        var ids=java.util.stream.Stream.of((String)a.get("id"),(String)b.get("id")).sorted(java.util.Comparator.reverseOrder()).toList();
        for(int page=1;page<=2;page++) {
            var result=request(HttpMethod.GET,"/api/v1/posts?size=1&page="+page,null,null);
            var items=(java.util.List<Map>)result.getBody().get("items");
            assertThat(items.getFirst().get("id")).isEqualTo(ids.get(page-1));
        }
        long first=clock.fixed;clock.fixed=first+1000;
        var retry=request(HttpMethod.POST,"/api/v1/admin/posts/"+a.get("id")+"/review",adm,Map.of("revision",1,"decision","APPROVE"));
        assertThat(((Number)retry.getBody().get("firstPublishedAt")).longValue()).isEqualTo(first);
        for(String query:java.util.List.of("page=0","page=no","size=0","size=101"))
            assertThat(request(HttpMethod.GET,"/api/v1/posts?"+query,null,null).getStatusCode().value()).isEqualTo(400);
    }
    @Test void missingAndMalformedBodiesAndReviewFieldsAreRejected() {
        assertThat(http.postForEntity("/api/v1/auth/register",Map.of(),Map.class).getStatusCode().value()).isEqualTo(400);
        String token=registerUser(),adm=admin();var p=post(token);String path="/api/v1/admin/posts/"+p.get("id")+"/review";
        for(var body:java.util.List.of(Map.of("revision",1,"decision","REJECT"),Map.of("decision","APPROVE"),Map.of("revision",1,"decision","OTHER"),Map.of("revision",1,"decision","APPROVE","reason","unexpected")))
            assertThat(request(HttpMethod.POST,path,adm,body).getStatusCode().value()).isEqualTo(400);
        assertThat(request(HttpMethod.POST,"/api/v1/posts",token,Map.of("title"," ","body","text")).getStatusCode().value()).isEqualTo(400);
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange("/api/v1/auth/register",HttpMethod.POST,new HttpEntity<>("{",headers),Map.class).getStatusCode().value()).isEqualTo(400);
    }

    @Test void successfulReadsNeverExtendSessionExpiry() {
        String token=registerUser();var p=post(token);String path="/api/v1/posts/"+p.get("id");
        clock.offset=Duration.ofHours(23).toMillis();
        assertThat(request(HttpMethod.GET,path,token,null).getStatusCode().value()).isEqualTo(200);
        clock.offset=Duration.ofHours(24).toMillis()+1000;
        assertThat(request(HttpMethod.GET,path,token,null).getStatusCode().value()).isEqualTo(401);
        assertThat(request(HttpMethod.GET,path,null,null).getStatusCode().value()).isEqualTo(404);
    }
    @ParameterizedTest @CsvSource({"username,3","username,33","password,7","password,65","nickname,33"})
    void registrationRejectsOutOfRangeInput(String field,int length) {
        var input=new java.util.HashMap<String,Object>(Map.of("username","u"+UUID.randomUUID().toString().substring(0,12).replace("-","_"),"password","password123"));
        input.put(field,"a".repeat(length));
        assertThat(http.postForEntity("/api/v1/auth/register",input,Map.class).getStatusCode().value()).isEqualTo(400);
    }
    @Test void duplicateUsernameIsCaseInsensitiveAndLoginAcceptsOriginalPassword() {
        String name="u"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        var input=Map.of("username",name,"password","密".repeat(64),"nickname","😀".repeat(32));
        assertThat(http.postForEntity("/api/v1/auth/register",input,Map.class).getStatusCode().value()).isEqualTo(201);
        assertThat(http.postForEntity("/api/v1/auth/register",Map.of("username",name.toUpperCase(),"password","password123"),Map.class).getStatusCode().value()).isEqualTo(409);
        assertThat(http.postForEntity("/api/v1/auth/login",Map.of("username",name.toUpperCase(),"password","密".repeat(64)),Map.class).getStatusCode().value()).isEqualTo(200);
    }
    @Test void inputCannotGrantAdminOrOverridePostIdentityOrState() {
        assertThat(http.postForEntity("/api/v1/auth/register",Map.of("username","attacker","password","password123","role","ADMIN"),Map.class).getStatusCode().value()).isEqualTo(400);
        String token=registerUser();
        for(String field:java.util.List.of("authorId","status","attachments")) {
            var body=new java.util.HashMap<String,Object>(Map.of("title","标题","body","正文"));body.put(field,"spoof");
            assertThat(request(HttpMethod.POST,"/api/v1/posts",token,body).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(request(HttpMethod.POST,"/api/v1/posts",admin(),Map.of("title","标题","body","正文")).getStatusCode().value()).isEqualTo(403);
        assertThat(request(HttpMethod.POST,"/api/v1/posts",null,Map.of("title","标题","body","正文")).getStatusCode().value()).isEqualTo(401);
        assertThat(request(HttpMethod.GET,"/api/v1/admin/posts",token,null).getStatusCode().value()).isEqualTo(403);
        assertThat(request(HttpMethod.GET,"/api/v1/admin/posts",null,null).getStatusCode().value()).isEqualTo(401);
    }
    @ParameterizedTest @CsvSource({"title,0","title,101","body,0","body,10001"})
    void textBoundsAreEnforced(String field,int length) {
        var body=new java.util.HashMap<String,Object>(Map.of("title","标题","body","正文"));body.put(field,"文".repeat(length));
        assertThat(request(HttpMethod.POST,"/api/v1/posts",registerUser(),body).getStatusCode().value()).isEqualTo(400);
    }
    @Test void maximumTextAndLiteralMarkupRoundTripWithoutFormatting() {
        String token=registerUser();
        String title="😀".repeat(100),body="<script>literal</script>\n"+"文".repeat(9975);
        var result=request(HttpMethod.POST,"/api/v1/posts",token,Map.of("title",title,"body",body));
        assertThat(result.getStatusCode().value()).isEqualTo(201);
        var read=request(HttpMethod.GET,"/api/v1/posts/"+result.getBody().get("id"),token,null);
        assertThat(read.getBody()).containsEntry("title",title).containsEntry("body",body);
    }
    @Test void reviewValidationAndRepeatedVerdictsPreserveOriginalResult() {
        String owner=registerUser(),adm=admin();var p=post(owner);String path="/api/v1/admin/posts/"+p.get("id")+"/review";
        assertThat(request(HttpMethod.POST,path,owner,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value()).isEqualTo(403);
        assertThat(request(HttpMethod.POST,path,adm,Map.of("revision",2,"decision","APPROVE")).getStatusCode().value()).isEqualTo(409);
        for(String reason:java.util.List.of(""," ","文".repeat(1001))) {
            assertThat(request(HttpMethod.POST,path,adm,Map.of("revision",1,"decision","REJECT","reason",reason)).getStatusCode().value()).isEqualTo(400);
        }
        var first=request(HttpMethod.POST,path,adm,Map.of("revision",1,"decision","REJECT","reason","文".repeat(1000)));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var retry=request(HttpMethod.POST,path,adm,Map.of("revision",1,"decision","REJECT","reason","不同原因"));
        assertThat(retry.getBody()).isEqualTo(first.getBody());
        assertThat(request(HttpMethod.POST,path,adm,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value()).isEqualTo(409);
    }
    @Test void concurrentOpposingReviewsHaveExactlyOneWinner() throws Exception {
        String owner=registerUser(),adm=admin();var p=post(owner);String path="/api/v1/admin/posts/"+p.get("id")+"/review";
        var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return request(HttpMethod.POST,path,adm,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value();});
            var b=executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return request(HttpMethod.POST,path,adm,Map.of("revision",1,"decision","REJECT","reason","违规")).getStatusCode().value();});
            assertThat(java.util.List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        String state=(String)request(HttpMethod.GET,"/api/v1/posts/"+p.get("id"),owner,null).getBody().get("status");
        assertThat(request(HttpMethod.GET,"/api/v1/posts/"+p.get("id"),null,null).getStatusCode().value()).isEqualTo(state.equals("PUBLISHED")?200:404);
    }
    @Test void administratorCanVerifyAllRealDependencies() {
        var result=request(HttpMethod.GET,"/api/v1/admin/dependencies",admin(),null);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat((String)result.getBody().get("databaseVersion")).containsIgnoringCase("openGauss");
        assertThat(result.getBody()).containsEntry("redis","PONG").containsEntry("rabbitmq","UP");
        assertThat(request(HttpMethod.GET,"/api/v1/admin/dependencies",registerUser(),null).getStatusCode().value()).isEqualTo(403);
    }
    String registerUser() {
        String name="u"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        assertThat(http.postForEntity("/api/v1/auth/register",Map.of("username",name,"password","password123"),Map.class).getStatusCode().value()).isEqualTo(201);
        return (String)http.postForEntity("/api/v1/auth/login",Map.of("username",name,"password","password123"),Map.class).getBody().get("token");
    }
    ResponseEntity<Map> request(HttpMethod method,String path,String token,Object body) {
        var headers=new HttpHeaders(); if(token!=null) headers.setBearerAuth(token);
        return http.exchange(path,method,new HttpEntity<>(body,headers),Map.class);
    }
    String admin() { return (String)http.postForEntity("/api/v1/auth/login",Map.of("username","admin","password","admin123"),Map.class).getBody().get("token"); }
    @Test void rejectionReasonIsVisibleOnlyToAuthorAndAdmin() {
        String owner=registerUser(), other=registerUser(); var p=post(owner);
        String path="/api/v1/posts/"+p.get("id");
        var result=request(HttpMethod.POST,"/api/v1/admin/posts/"+p.get("id")+"/review",admin(),Map.of("revision",1,"decision","REJECT","reason","请修改内容"));
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(request(HttpMethod.GET,path,owner,null).getBody()).containsEntry("status","REJECTED").containsEntry("rejectionReason","请修改内容");
        assertThat(request(HttpMethod.GET,path,other,null).getStatusCode().value()).isEqualTo(404);
        assertThat(request(HttpMethod.GET,path,null,null).getStatusCode().value()).isEqualTo(404);
    }
    Map post(String token) { return request(HttpMethod.POST,"/api/v1/posts",token,Map.of("title","作品","body","原创正文")).getBody(); }
    @Test void listsFilterStateAndPaginateWithStableOrder() {
        String owner=registerUser(), admin=admin(); var pending=post(owner); var published=post(owner);
        request(HttpMethod.POST,"/api/v1/admin/posts/"+published.get("id")+"/review",admin,Map.of("revision",1,"decision","APPROVE"));
        var page=request(HttpMethod.GET,"/api/v1/posts?size=1",null,null);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getBody()).containsEntry("size",1).containsEntry("page",1);
        var items=(java.util.List<Map>)page.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("id",published.get("id")).doesNotContainKeys("body","rejectionReason");
        var queue=request(HttpMethod.GET,"/api/v1/admin/posts?status=PENDING",admin,null);
        assertThat(queue.getStatusCode().value()).isEqualTo(200);
        assertThat((java.util.List<Map>)queue.getBody().get("items")).allSatisfy(p->assertThat(p).containsEntry("status","PENDING"));
        assertThat(request(HttpMethod.GET,"/api/v1/posts?size=101",null,null).getStatusCode().value()).isEqualTo(400);
    }
    @Test void approvalPublishesTextForVisitors() {
        var p=post(registerUser()); String path="/api/v1/posts/"+p.get("id");
        var reviewed=request(HttpMethod.POST,"/api/v1/admin/posts/"+p.get("id")+"/review",admin(),Map.of("revision",1,"decision","APPROVE"));
        assertThat(reviewed.getStatusCode().value()).isEqualTo(200);
        var publicPost=request(HttpMethod.GET,path,null,null);
        assertThat(publicPost.getStatusCode().value()).isEqualTo(200);
        assertThat(publicPost.getBody()).containsEntry("status","PUBLISHED").containsEntry("body","原创正文");
        assertThat(publicPost.getBody().get("firstPublishedAt")).isNotNull();
        assertThat(publicPost.getBody()).doesNotContainKey("rejectionReason");
    }
    @Test void textSubmissionIsPendingAndHiddenFromVisitors() {
        String token=registerUser();
        var response=request(HttpMethod.POST,"/api/v1/posts",token,Map.of("title","原创标题","body","第一行\n第二行"));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsEntry("status","PENDING").containsEntry("body","第一行\n第二行");
        String path="/api/v1/posts/"+response.getBody().get("id");
        assertThat(request(HttpMethod.GET,path,null,null).getStatusCode().value()).isEqualTo(404);
        assertThat(request(HttpMethod.GET,path,token,null).getStatusCode().value()).isEqualTo(200);
    }

    @Test void loginIssuesFixedLifetimeTokenAndRejectsWrongPassword() {
        var response=http.postForEntity("/api/v1/auth/login",Map.of("username","admin","password","admin123"),Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((String)response.getBody().get("token")).hasSize(64);
        assertThat(((Number)response.getBody().get("expiresAt")).longValue()).isBetween(System.currentTimeMillis()+86_390_000L,System.currentTimeMillis()+86_410_000L);
        assertThat(http.postForEntity("/api/v1/auth/login",Map.of("username","admin","password","wrong"),Map.class).getStatusCode().value()).isEqualTo(401);
    }

    @Test void registrationReturnsOrdinaryUserAndDefaultAvatar() {
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var response = http.postForEntity("/api/v1/auth/register",
                Map.of("username", username, "password", "password123"), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsEntry("nickname", username).containsEntry("role", "USER")
                .containsEntry("avatarUrl", "/assets/default-avatar.svg");
        assertThat(response.getBody()).doesNotContainKeys("password", "passwordHash", "token");
    }
}
