package campus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
class RemainingFlowsTest {
    @Autowired TestRestTemplate http;
    ResponseEntity<Map> call(HttpMethod method,String path,String token,Object body) {
        var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);
        return http.exchange("/api/v1"+path,method,new HttpEntity<>(body,h),Map.class);
    }
    String user() {
        String name="u"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        assertThat(call(HttpMethod.POST,"/auth/register",null,Map.of("username",name,"password","password123")).getStatusCode().value()).isEqualTo(201);
        return (String)call(HttpMethod.POST,"/auth/login",null,Map.of("username",name,"password","password123")).getBody().get("token");
    }
    String admin() {return (String)call(HttpMethod.POST,"/auth/login",null,Map.of("username","admin","password","admin123")).getBody().get("token");}
    Map post(String token) {return call(HttpMethod.POST,"/posts",token,Map.of("title","作品","body","正文")).getBody();}
    Map publish(String token,String admin) {
        var p=post(token);return call(HttpMethod.POST,"/admin/posts/"+p.get("id")+"/review",admin,Map.of("revision",1,"decision","APPROVE")).getBody();
    }
    @Test void batchOneSpecBoundaryAudit() {
        String u=user(),a=admin();var p=post(u);String id=(String)p.get("id");
        assertThat(call(HttpMethod.POST,"/admin/posts/"+id+"/review",a,Map.of("revision",1,"decision","REJECT")).getStatusCode().value()).isEqualTo(400);
        call(HttpMethod.POST,"/admin/posts/"+id+"/review",a,Map.of("revision",1,"decision","REJECT","reason","原因"));
        assertThat(call(HttpMethod.GET,"/posts/"+id,a,null).getBody()).containsEntry("rejectionReason","原因");
        var result=call(HttpMethod.GET,"/admin/posts",a,null);
        assertThat(result.getBody()).containsEntry("page",1).containsEntry("size",20);
        assertThat(call(HttpMethod.GET,"/admin/posts?status=REJECTED",a,null).getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.POST,"/admin/posts/missing/review",a,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.GET,"/posts/missing",null,null).getStatusCode().value()).isEqualTo(404);
    }
    @Test void unicodeWhitespaceIsNotValidPostContent(){
        String u=user();
        assertThat(call(HttpMethod.POST,"/posts",u,Map.of("title","\u00a0","body","正文")).getStatusCode().value()).isEqualTo(400);
    }
    @Test void nicknameChangeAppearsInAuthenticatedSingleBatchAndPublicAuthors() {
        String u=user(),a=admin();var p=publish(u,a);
        var changed=call(HttpMethod.PATCH,"/me",u,Map.of("nickname","新昵称"));
        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        String id=(String)changed.getBody().get("id");
        assertThat(call(HttpMethod.GET,"/users/"+id,null,null).getStatusCode().value()).isEqualTo(401);
        var info=call(HttpMethod.GET,"/users/"+id,u,null);
        assertThat(info.getBody()).containsEntry("nickname","新昵称").doesNotContainKeys("username","password","role");
        var batch=call(HttpMethod.POST,"/users/batch",u,Map.of("ids",List.of(id,id,"missing")));
        assertThat(batch.getStatusCode().value()).isEqualTo(200);
        assertThat((List)batch.getBody().get("items")).hasSize(1);
        assertThat((List)batch.getBody().get("missingIds")).containsExactly("missing");
        assertThat((Map)call(HttpMethod.GET,"/posts/"+p.get("id"),null,null).getBody().get("author")).containsEntry("nickname","新昵称");
        assertThat(call(HttpMethod.PATCH,"/me",u,Map.of("username","other")).getStatusCode().value()).isEqualTo(400);
    }
    @Test void interactionsAreIdempotentImmediateAndSurviveReReview() {
        String u=user(),other=user(),a=admin();var p=publish(u,a);String path="/posts/"+p.get("id");
        for(int i=0;i<2;i++)assertThat(call(HttpMethod.PUT,path+"/likes",other,null).getStatusCode().value()).isEqualTo(200);
        call(HttpMethod.PUT,path+"/favorites",other,null);
        var state=call(HttpMethod.GET,path,other,null).getBody();
        assertThat(state).containsEntry("likeCount",1).containsEntry("favoriteCount",1).containsEntry("liked",true).containsEntry("favorited",true);
        assertThat((List)call(HttpMethod.GET,"/me/likes",other,null).getBody().get("items")).extracting(x->((Map)x).get("id")).contains(p.get("id"));
        call(HttpMethod.PUT,path,u,Map.of("revision",1,"title","修改","body","新正文","resourceIds",List.of()));
        assertThat((List)call(HttpMethod.GET,"/me/likes",other,null).getBody().get("items")).extracting(x->((Map)x).get("id")).doesNotContain(p.get("id"));
        assertThat(call(HttpMethod.PUT,path+"/likes",other,null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.DELETE,path+"/likes",other,null).getStatusCode().value()).isEqualTo(200);
        call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",2,"decision","APPROVE"));
        assertThat(call(HttpMethod.GET,path,other,null).getBody()).containsEntry("likeCount",0).containsEntry("favoriteCount",1);
        call(HttpMethod.DELETE,path,u,null);
        assertThat(call(HttpMethod.DELETE,path+"/favorites",other,null).getStatusCode().value()).isEqualTo(404);
    }
    @Test void commentsFlattenRepliesCascadeDeleteAndPreserveOnlyOwnHistory() {
        String owner=user(),other=user(),a=admin();var p=publish(owner,a);String postPath="/posts/"+p.get("id");
        var root=call(HttpMethod.POST,postPath+"/comments",owner,Map.of("body","一级评论"));
        assertThat(root.getStatusCode().value()).isEqualTo(201);
        String rootId=(String)root.getBody().get("id");
        var reply=call(HttpMethod.POST,"/comments/"+rootId+"/replies",other,Map.of("body","回复一")).getBody();
        var reply2=call(HttpMethod.POST,"/comments/"+reply.get("id")+"/replies",owner,Map.of("body","回复二")).getBody();
        assertThat(reply2).containsEntry("rootId",rootId).containsEntry("replyToId",reply.get("id"));
        assertThat((List)call(HttpMethod.GET,"/comments/"+rootId+"/replies",null,null).getBody().get("items")).hasSize(2);
        call(HttpMethod.DELETE,"/comments/"+reply.get("id"),other,null);
        assertThat(call(HttpMethod.GET,"/comments/"+reply2.get("id"),null,null).getBody()).containsEntry("replyTargetDeleted",true);
        assertThat(call(HttpMethod.POST,"/comments/"+reply.get("id")+"/replies",owner,Map.of("body","不可回复")).getStatusCode().value()).isEqualTo(404);
        call(HttpMethod.DELETE,"/comments/"+rootId,owner,null);
        assertThat((List)call(HttpMethod.GET,postPath+"/comments",null,null).getBody().get("items")).isEmpty();
        var history=(List<Map>)call(HttpMethod.GET,"/me/comments",other,null).getBody().get("items");
        assertThat(history).anySatisfy(c->assertThat(c).containsEntry("body","回复一").containsEntry("deleted",true));
        assertThat(call(HttpMethod.GET,"/comments/"+reply.get("id"),other,null).getStatusCode().value()).isEqualTo(410);
        assertThat(call(HttpMethod.GET,"/comments/"+reply.get("id"),owner,null).getStatusCode().value()).isEqualTo(404);
    }
    @Test void hotFeedFreezesOrderRefreshesAndFiltersHiddenPostsImmediately() {
        String owner=user(),a=admin();
        var old=call(HttpMethod.GET,"/feed/hot?size=1",null,null);
        assertThat(old.getStatusCode().value()).isEqualTo(200);
        var oldItems=(List<Map>)old.getBody().get("items");long max=0;
        if(!oldItems.isEmpty()){var item=oldItems.getFirst();max=((Number)item.get("likeCount")).longValue()+2*((Number)item.get("favoriteCount")).longValue();}
        int n=(int)(max/3)+2;assertThat(n).isLessThan(100);
        var first=publish(owner,a);var second=publish(owner,a);var users=new ArrayList<String>();
        for(int i=0;i<n;i++){
            String u=user();users.add(u);
            call(HttpMethod.PUT,"/posts/"+second.get("id")+"/likes",u,null);call(HttpMethod.PUT,"/posts/"+second.get("id")+"/favorites",u,null);
            if(i<n-1){call(HttpMethod.PUT,"/posts/"+first.get("id")+"/likes",u,null);call(HttpMethod.PUT,"/posts/"+first.get("id")+"/favorites",u,null);}
        }
        var initial=call(HttpMethod.GET,"/feed/hot?size=1",null,null).getBody();String snapshot=(String)initial.get("snapshotId");
        assertThat(((List<Map>)initial.get("items")).getFirst()).containsEntry("id",second.get("id"));
        for(String u:users){call(HttpMethod.DELETE,"/posts/"+second.get("id")+"/likes",u,null);call(HttpMethod.DELETE,"/posts/"+second.get("id")+"/favorites",u,null);}
        assertThat(((List<Map>)call(HttpMethod.GET,"/feed/hot?size=1&snapshotId="+snapshot,null,null).getBody().get("items")).getFirst()).containsEntry("id",second.get("id"));
        assertThat(((List<Map>)call(HttpMethod.GET,"/feed/hot?size=1",null,null).getBody().get("items")).getFirst()).containsEntry("id",first.get("id"));
        call(HttpMethod.PUT,"/posts/"+second.get("id"),owner,Map.of("revision",1,"title","重审","body","待审","resourceIds",List.of()));
        assertThat((List)call(HttpMethod.GET,"/feed/hot?size=1&snapshotId="+snapshot,null,null).getBody().get("items")).isEmpty();
        call(HttpMethod.DELETE,"/posts/"+first.get("id"),owner,null);call(HttpMethod.DELETE,"/posts/"+second.get("id"),owner,null);
    }
    @Test void editResubmitsLatestRevisionAndDeleteHidesFromEveryone() {
        String u=user(),a=admin();var p=publish(u,a);String path="/posts/"+p.get("id");Object first=p.get("firstPublishedAt");
        var edited=call(HttpMethod.PUT,path,u,Map.of("revision",1,"title","新标题","body","新正文","resourceIds",List.of()));
        assertThat(edited.getStatusCode().value()).isEqualTo(200);
        assertThat(edited.getBody()).containsEntry("status","PENDING").containsEntry("revision",2);
        assertThat(call(HttpMethod.GET,path,null,null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value()).isEqualTo(409);
        var approved=call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",2,"decision","APPROVE"));
        assertThat(approved.getBody()).containsEntry("firstPublishedAt",first);
        assertThat(call(HttpMethod.DELETE,path,user(),null).getStatusCode().value()).isEqualTo(404);
        assertThat(call(HttpMethod.DELETE,path,u,null).getStatusCode().value()).isEqualTo(200);
        for(String token:Arrays.asList(null,u,a))assertThat(call(HttpMethod.GET,path,token,null).getStatusCode().value()).isEqualTo(404);
    }
}
