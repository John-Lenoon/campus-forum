package campus;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
@Import(ForumHttpTest.TimeConfig.class)
class CrossModuleAcceptanceTest {
    @Autowired TestRestTemplate http;@Autowired ForumHttpTest.TestClock clock;
    RemainingFlowsTest f;
    @BeforeEach void setup(){clock.offset=0;clock.fixed=null;f=new RemainingFlowsTest();f.http=http;}
    @AfterEach void reset(){clock.offset=0;clock.fixed=null;}
    @Test void allEditableStatesReReviewKeepHistoryAndDiscardOnlyCurrentReason(){
        String u=f.user(),other=f.user(),a=f.admin();var p=f.post(u);String path="/posts/"+p.get("id");
        assertThat(f.call(HttpMethod.DELETE,path+"/likes",other,null).getStatusCode().value()).isEqualTo(200);
        assertThat(f.call(HttpMethod.POST,path+"/comments",u,Map.of("body","不可新增")).getStatusCode().value()).isEqualTo(404);
        assertThat(f.call(HttpMethod.PUT,path,u,Map.of("revision",1,"title","待审修改","body","正文","resourceIds",List.of())).getBody()).containsEntry("revision",2);
        assertThat(f.call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value()).isEqualTo(409);
        f.call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",2,"decision","REJECT","reason","第一轮原因"));
        assertThat(f.call(HttpMethod.GET,path,u,null).getBody()).containsEntry("rejectionReason","第一轮原因");
        var edited=f.call(HttpMethod.PUT,path,u,Map.of("revision",2,"title","驳回后重提","body","正文","resourceIds",List.of()));
        assertThat(edited.getBody()).containsEntry("revision",3).containsEntry("rejectionReason",null);
        var approved=f.call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",3,"decision","APPROVE")).getBody();Object first=approved.get("firstPublishedAt");
        f.call(HttpMethod.PUT,path+"/likes",other,null);f.call(HttpMethod.PUT,path+"/favorites",other,null);
        var c=f.call(HttpMethod.POST,path+"/comments",other,Map.of("body","保留到本人历史")).getBody();
        f.call(HttpMethod.PUT,path,u,Map.of("revision",3,"title","已发布修改","body","正文","resourceIds",List.of()));
        assertThat(f.call(HttpMethod.GET,path+"/comments",null,null).getStatusCode().value()).isEqualTo(404);
        assertThat(f.call(HttpMethod.DELETE,"/comments/"+c.get("id"),other,null).getStatusCode().value()).isEqualTo(200);
        f.call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",4,"decision","REJECT","reason","当前原因"));
        assertThat(f.call(HttpMethod.DELETE,path+"/likes",other,null).getStatusCode().value()).isEqualTo(200);
        f.call(HttpMethod.PUT,path,u,Map.of("revision",4,"title","再次重提","body","正文","resourceIds",List.of()));
        var restored=f.call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",5,"decision","APPROVE")).getBody();
        assertThat(restored).containsEntry("firstPublishedAt",first).containsEntry("likeCount",0).containsEntry("favoriteCount",1);
        assertThat((List)f.call(HttpMethod.GET,path+"/comments",null,null).getBody().get("items")).isEmpty();
        assertThat(f.call(HttpMethod.DELETE,path,a,null).getStatusCode().value()).isEqualTo(200);
        assertThat(f.call(HttpMethod.GET,path,a,null).getStatusCode().value()).isEqualTo(404);
        assertThat((List<Map>)f.call(HttpMethod.GET,"/me/comments",other,null).getBody().get("items")).anySatisfy(x->assertThat(x).containsEntry("body","保留到本人历史").containsEntry("deleted",true));
        assertThat(f.call(HttpMethod.GET,"/comments/"+c.get("id"),other,null).getBody()).containsEntry("message","该评论已删除");
    }
    @Test void commentPaginationBoundariesAndAdminPostCascadePreserveEachOwnersHistory(){
        String u=f.user(),other=f.user(),a=f.admin();var p=f.publish(u,a);String path="/posts/"+p.get("id");
        var root=f.call(HttpMethod.POST,path+"/comments",u,Map.of("body","😀".repeat(1000)));
        assertThat(root.getStatusCode().value()).isEqualTo(201);
        assertThat(f.call(HttpMethod.POST,path+"/comments",u,Map.of("body","😀".repeat(1001))).getStatusCode().value()).isEqualTo(400);
        assertThat(f.call(HttpMethod.POST,path+"/comments",u,Map.of("body","\u00a0")).getStatusCode().value()).isEqualTo(400);
        var reply=f.call(HttpMethod.POST,"/comments/"+root.getBody().get("id")+"/replies",other,Map.of("body","他人回复")).getBody();
        f.call(HttpMethod.POST,path+"/comments",u,Map.of("body","另一根"));
        var asc=(List<Map>)f.call(HttpMethod.GET,path+"/comments?order=asc",null,null).getBody().get("items");
        var desc=(List<Map>)f.call(HttpMethod.GET,path+"/comments?order=desc",null,null).getBody().get("items");
        assertThat(desc.stream().map(c->c.get("id")).toList()).containsExactlyElementsOf(asc.reversed().stream().map(c->c.get("id")).toList());
        assertThat((List)f.call(HttpMethod.GET,path+"/comments?size=1",null,null).getBody().get("items")).hasSize(1);
        assertThat(f.call(HttpMethod.DELETE,"/comments/"+root.getBody().get("id"),other,null).getStatusCode().value()).isEqualTo(404);
        assertThat(f.call(HttpMethod.DELETE,"/comments/"+root.getBody().get("id"),a,null).getStatusCode().value()).isEqualTo(200);
        assertThat(f.call(HttpMethod.GET,"/comments/"+reply.get("id"),other,null).getStatusCode().value()).isEqualTo(410);
        var alive=f.call(HttpMethod.POST,path+"/comments",other,Map.of("body","随帖子删除")).getBody();
        f.call(HttpMethod.DELETE,path,u,null);
        assertThat(f.call(HttpMethod.GET,"/comments/"+alive.get("id"),other,null).getStatusCode().value()).isEqualTo(410);
        assertThat(f.call(HttpMethod.GET,"/comments/"+alive.get("id"),a,null).getStatusCode().value()).isEqualTo(404);
        assertThat((List<Map>)f.call(HttpMethod.GET,"/me/comments",u,null).getBody().get("items")).noneSatisfy(c->assertThat(c).containsEntry("body","他人回复"));
    }
    @Test void concurrentLikesAndPersonalOrderingReflectCompletedActions()throws Exception{
        String u=f.user(),other=f.user(),a=f.admin();var p=f.publish(u,a);String path="/posts/"+p.get("id");
        try(var pool=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Callable<Integer>>();for(int i=0;i<16;i++)tasks.add(()->f.call(HttpMethod.PUT,path+"/likes",other,null).getStatusCode().value());
            for(var result:pool.invokeAll(tasks))assertThat(result.get(15,TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(f.call(HttpMethod.GET,path,other,null).getBody()).containsEntry("likeCount",1).containsEntry("liked",true);
        assertThat(f.call(HttpMethod.PUT,path+"/favorites",a,null).getStatusCode().value()).isEqualTo(403);
        assertThat(f.call(HttpMethod.PUT,path+"/favorites",null,null).getStatusCode().value()).isEqualTo(401);
        var second=f.publish(u,a);clock.fixed=System.currentTimeMillis()+1000;
        f.call(HttpMethod.PUT,"/posts/"+second.get("id")+"/likes",other,null);
        var list=(List<Map>)f.call(HttpMethod.GET,"/me/likes",other,null).getBody().get("items");assertThat(list.getFirst()).containsEntry("id",second.get("id"));
        clock.fixed+=1000;f.call(HttpMethod.DELETE,path+"/likes",other,null);f.call(HttpMethod.PUT,path+"/likes",other,null);
        assertThat(((List<Map>)f.call(HttpMethod.GET,"/me/likes",other,null).getBody().get("items")).getFirst()).containsEntry("id",p.get("id"));
    }
    @Test void expiredHotSnapshotRequiresExplicitRefresh(){
        var first=f.call(HttpMethod.GET,"/feed/hot",null,null).getBody();String id=(String)first.get("snapshotId");
        clock.offset=601000;
        var expired=f.call(HttpMethod.GET,"/feed/hot?snapshotId="+id,null,null);
        assertThat(expired.getStatusCode().value()).isEqualTo(410);assertThat(expired.getBody()).containsEntry("code","REFRESH_REQUIRED");
        assertThat(f.call(HttpMethod.GET,"/feed/hot",null,null).getBody().get("snapshotId")).isNotEqualTo(id);
    }
    @Test void bindingAndUnknownRouteErrorsUseStableCodes(){
        var missingHeaders=new org.springframework.http.HttpHeaders();missingHeaders.setBearerAuth(f.user());missingHeaders.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var missing=http.exchange("/api/v1/resources/images?purpose=POST",HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(new org.springframework.util.LinkedMultiValueMap<String,Object>(),missingHeaders),Map.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);assertThat(missing.getBody()).containsEntry("code","INVALID_REQUEST");
        var unknown=f.call(HttpMethod.GET,"/not-an-api-route",null,null);
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);assertThat(unknown.getBody()).containsEntry("code","NOT_FOUND");
    }
    @Test void authorCanDeletePendingAndRejectedAndAdminListNeverReturnsDeleted(){
        String u=f.user(),a=f.admin();var pending=f.post(u);var rejected=f.post(u);
        f.call(HttpMethod.POST,"/admin/posts/"+rejected.get("id")+"/review",a,Map.of("revision",1,"decision","REJECT","reason","原因"));
        for(var p:List.of(pending,rejected)){
            assertThat(f.call(HttpMethod.DELETE,"/posts/"+p.get("id"),u,null).getStatusCode().value()).isEqualTo(200);
            assertThat(f.call(HttpMethod.POST,"/admin/posts/"+p.get("id")+"/review",a,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value()).isEqualTo(404);
        }
        assertThat((List<Map>)f.call(HttpMethod.GET,"/admin/posts?size=100",a,null).getBody().get("items")).noneSatisfy(p->assertThat(p.get("id")).isIn(pending.get("id"),rejected.get("id")));
    }
}
