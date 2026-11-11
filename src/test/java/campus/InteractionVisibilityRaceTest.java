package campus;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
@Import(InteractionVisibilityRaceTest.Fixture.class)
class InteractionVisibilityRaceTest {
    static class Gate { volatile String id; final ThreadLocal<Boolean> listRequest=new ThreadLocal<>(); CountDownLatch read=new CountDownLatch(1), release=new CountDownLatch(1); }
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class GateInterceptor implements Interceptor {
        final Gate gate; GateInterceptor(Gate gate) { this.gate=gate; }
        public Object intercept(Invocation call) throws Throwable {
            var statement=(MappedStatement)call.getArgs()[0];
            Object result=call.proceed();
            if (Boolean.TRUE.equals(gate.listRequest.get()) && statement.getId().equals("campus.mapper.InteractionMapper.pagePublishedIds") && ((List<?>)result).contains(gate.id)) {
                gate.read.countDown(); if (!gate.release.await(30,TimeUnit.SECONDS)) throw new IllegalStateException("list gate timeout");
            }
            return result;
        }
    }
    @TestConfiguration static class Fixture {
        @Bean Gate interactionGate() { return new Gate(); }
        @Bean Interceptor interactionGateInterceptor(Gate gate) { return new GateInterceptor(gate); }
        @Bean OncePerRequestFilter interactionMarker(Gate gate) { return new OncePerRequestFilter() {
            protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                if (req.getRequestURI().equals("/api/v1/me/likes")) gate.listRequest.set(true);
                try { chain.doFilter(req,res); } finally { gate.listRequest.remove(); }
            }
        }; }
    }
    @Autowired TestRestTemplate http; @Autowired Gate gate;
    @Test void authorsListDoesNotExposePostResubmittedAfterIdsWereRead() throws Exception { race(true); }
    @Test void otherUsersListDoesNotFailWhenPostIsResubmittedAfterIdsWereRead() throws Exception { race(false); }
    void race(boolean self) throws Exception {
        var api=new RemainingFlowsTest(); api.http=http;
        String owner=api.user(), viewer=self?owner:api.user(), admin=api.admin();
        String id=(String)api.publish(owner,admin).get("id"), path="/posts/"+id;
        assertThat(api.call(HttpMethod.PUT,path+"/likes",viewer,null).getStatusCode().value()).isEqualTo(200);
        gate.id=id; gate.read=new CountDownLatch(1); gate.release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            try {
                var listing=pool.submit(()->api.call(HttpMethod.GET,"/me/likes",viewer,null));
                assertThat(gate.read.await(15,TimeUnit.SECONDS)).as("published IDs read before edit").isTrue();
                assertThat(api.call(HttpMethod.PUT,path,owner,Map.of("revision",1,"title","修改","body","正文","resourceIds",List.of())).getBody()).containsEntry("status","PENDING");
                gate.release.countDown();
                var result=listing.get(15,TimeUnit.SECONDS);
                assertThat(result.getStatusCode().value()).isEqualTo(200);
                assertThat((List<Map>)result.getBody().get("items")).noneSatisfy(item->assertThat(item.get("id")).isEqualTo(id));
            } finally { gate.release.countDown(); }
        }
    }
}
