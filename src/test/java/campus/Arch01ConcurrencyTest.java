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
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
@Import(Arch01ConcurrencyTest.Fixture.class)
class Arch01ConcurrencyTest {
    @Autowired TestRestTemplate http;
    @Autowired Gate gate;
    @Autowired WaitObserver observer;

    interface WaitObserver {
        @Select("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND waiting=true AND query LIKE '%forum_posts%' AND query LIKE '%FOR UPDATE%'")
        long waitingPostLocks();
    }

    static class Gate {
        final ThreadLocal<String> request = new ThreadLocal<>();
        volatile String deleting;
        volatile String replying;
        volatile CountDownLatch locked;
        volatile CountDownLatch replyAtLock;
        volatile CountDownLatch releaseDelete;
        void arm(String deleting, String replying) {
            this.deleting = deleting; this.replying = replying;
            locked = new CountDownLatch(1); replyAtLock = new CountDownLatch(1);
            releaseDelete = new CountDownLatch(1);
        }
    }

    @Intercepts(@Signature(type=Executor.class, method="query", args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class LockGate implements Interceptor {
        final Gate gate;
        LockGate(Gate gate) { this.gate = gate; }
        public Object intercept(Invocation call) throws Throwable {
            MappedStatement statement = (MappedStatement) call.getArgs()[0];
            if (!statement.getId().equals("campus.mapper.PostMapper.lockActive")) return call.proceed();
            String request = gate.request.get();
            if (request != null && request.equals("DELETE " + gate.deleting)) {
                Object result = call.proceed();
                gate.locked.countDown();
                if (!gate.releaseDelete.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("delete gate timeout");
                return result;
            }
            if (request != null && request.equals("POST " + gate.replying)) gate.replyAtLock.countDown();
            return call.proceed();
        }
    }

    @TestConfiguration
    @MapperScan("campus.mapper")
    static class Fixture {
        @Bean Gate gate() { return new Gate(); }
        @Bean Interceptor lockGate(Gate gate) { return new LockGate(gate); }
        @Bean MapperFactoryBean<WaitObserver> waitObserver(SqlSessionFactory factory) {
            MapperFactoryBean<WaitObserver> bean = new MapperFactoryBean<>(WaitObserver.class);
            bean.setSqlSessionFactory(factory); return bean;
        }
        @Bean OncePerRequestFilter tagRequests(Gate gate) {
            return new OncePerRequestFilter() {
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                        throws ServletException, IOException {
                    gate.request.set(req.getMethod() + " " + req.getRequestURI());
                    try { chain.doFilter(req, res); } finally { gate.request.remove(); }
                }
            };
        }
    }

    @Test void deletingTargetReplyWhileCreatingReplyRejectsCreation() throws Exception { race(false); }
    @Test void deletingRootWhileCreatingReplyCannotEscapeCascade() throws Exception { race(true); }

    void race(boolean deleteRoot) throws Exception {
        var api = new RemainingFlowsTest(); api.http = http;
        String owner = api.user(), writer = api.user(), admin = api.admin();
        String post = (String) api.publish(owner, admin).get("id");
        String root = (String) api.call(HttpMethod.POST, "/posts/"+post+"/comments", owner, Map.of("body","根评论")).getBody().get("id");
        String target = (String) api.call(HttpMethod.POST, "/comments/"+root+"/replies", owner, Map.of("body","目标回复")).getBody().get("id");
        String marker = "竞争回复-" + java.util.UUID.randomUUID();
        String deletePath = "/comments/" + (deleteRoot ? root : target);
        String replyPath = "/comments/" + target + "/replies";
        gate.arm("/api/v1" + deletePath, "/api/v1" + replyPath);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var deletion = pool.submit(() -> api.call(HttpMethod.DELETE, deletePath, owner, null));
            assertThat(gate.locked.await(15, TimeUnit.SECONDS)).as("删除事务已持有帖子锁").isTrue();
            var creation = pool.submit(() -> api.call(HttpMethod.POST, replyPath, writer, Map.of("body",marker)));
            assertThat(gate.replyAtLock.await(15, TimeUnit.SECONDS)).as("回复已读目标并尝试获取帖子锁").isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (observer.waitingPostLocks() == 0 && System.nanoTime() < deadline)
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            assertThat(observer.waitingPostLocks()).as("数据库确认回复正在等待帖子锁").isGreaterThan(0);
            gate.releaseDelete.countDown();
            assertThat(deletion.get(15, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            var result = creation.get(15, TimeUnit.SECONDS);
            var history = api.call(HttpMethod.GET, "/me/comments", writer, null).getBody();
            org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
                soft.assertThat(result.getStatusCode().value()).as("reply HTTP status").isEqualTo(404);
                soft.assertThat((List<Map>) history.get("items")).as("no successful reply").noneMatch(c -> marker.equals(c.get("body")));
            });
            assertThat(result.getBody()).containsEntry("code", "NOT_FOUND");
            if (deleteRoot) {
                assertThat(api.call(HttpMethod.GET, "/comments/"+root+"/replies", writer, null).getStatusCode().value()).isEqualTo(404);
                assertThat(api.call(HttpMethod.GET, "/comments/"+target, owner, null).getStatusCode().value()).isEqualTo(410);
            }
        } finally { gate.releaseDelete.countDown(); gate.deleting=null; gate.replying=null; }
    }
}
