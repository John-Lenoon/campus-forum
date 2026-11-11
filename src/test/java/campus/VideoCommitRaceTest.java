package campus;

import static org.assertj.core.api.Assertions.assertThat;
import campus.service.VideoService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.support.AbstractTestExecutionListener;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(VideoCommitRaceTest.Fixture.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners=VideoCommitRaceTest.Cleanup.class,mergeMode=TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class VideoCommitRaceTest {
    static final String SUFFIX=java.util.UUID.randomUUID().toString().replace("-", "").substring(0,12);
    static final String DATABASE="forum_video_commit_"+SUFFIX, QUEUE="campus-forum.video-commit."+SUFFIX;
    static boolean created;
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry p) throws Exception {
        if (!created) { Arch03ProcessInterruptionTest.sql("CREATE DATABASE "+DATABASE+" OWNER forum;"); created=true; }
        p.add("spring.datasource.url",()->"jdbc:postgresql://localhost:25432/"+DATABASE);
        p.add("forum.storage-root",()->"target/video-commit-"+SUFFIX);
        p.add("forum.video-queue",()->QUEUE);
        System.out.println("VIDEO-COMMIT isolated database="+DATABASE+" queue="+QUEUE);
    }
    public static class Cleanup extends AbstractTestExecutionListener implements Ordered {
        public int getOrder() { return 0; }
        public void afterTestClass(TestContext ignored) throws Exception {
            try {
                var p=new ProcessBuilder("docker","compose","exec","-T","rabbitmq","rabbitmqctl","delete_queue",QUEUE).redirectErrorStream(true).start();
                String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                if (!p.waitFor(30,TimeUnit.SECONDS) || (p.exitValue()!=0 && !out.contains("Queue not found"))) throw new IllegalStateException(out);
            } finally { if(created) { Arch03ProcessInterruptionTest.sql("DROP DATABASE "+DATABASE+";"); created=false; } }
        }
    }
    static class Gate {
        volatile String id; final ThreadLocal<Boolean> finishing=new ThreadLocal<>();
        final CountDownLatch committed=new CountDownLatch(1), release=new CountDownLatch(1), firstDone=new CountDownLatch(1);
        volatile CountDownLatch duplicateDone=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();
    }
    @Intercepts({@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),@Signature(type=Executor.class,method="commit",args={boolean.class})})
    static class CommitFault implements Interceptor {
        final Gate gate; CommitFault(Gate gate) { this.gate=gate; }
        public Object intercept(Invocation call) throws Throwable {
            if (call.getMethod().getName().equals("update")) {
                var ms=(MappedStatement)call.getArgs()[0];
                if (ms.getId().equals("campus.mapper.ResourceMapper.finishProcessing") && ((Map<?,?>)call.getArgs()[1]).get("id").equals(gate.id)) {
                    Object result=call.proceed(); gate.finishing.set(true); return result;
                }
            }
            if (call.getMethod().getName().equals("commit") && Boolean.TRUE.equals(gate.finishing.get())) {
                Object result=call.proceed(); gate.finishing.remove(); gate.committed.countDown();
                if (!gate.release.await(30,TimeUnit.SECONDS)) throw new IllegalStateException("commit gate timeout");
                throw new DataAccessResourceFailureException("injected completion acknowledgement loss");
            }
            return call.proceed();
        }
    }
    @TestConfiguration static class Fixture {
        @Bean Gate videoCommitGate() { return new Gate(); }
        @Bean Interceptor commitFault(Gate gate) { return new CommitFault(gate); }
        @Bean static BeanPostProcessor videoWorkerCapture(ObjectProvider<Gate> provider) { return new BeanPostProcessor() {
            public Object postProcessAfterInitialization(Object bean,String name) {
                if (!(bean instanceof VideoService)) return bean;
                ProxyFactory proxy=new ProxyFactory(bean); proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor)call->{
                    Gate gate=provider.getObject();
                    if (!call.getMethod().getName().equals("process") || !java.util.Objects.equals(gate.id,call.getArguments()[0])) return call.proceed();
                    int number=gate.calls.incrementAndGet();
                    try { return call.proceed(); } finally { if(number==1) gate.firstDone.countDown(); else gate.duplicateDone.countDown(); }
                }); return proxy.getProxy();
            }
        }; }
    }
    @Autowired TestRestTemplate http; @Autowired Gate gate; @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry registry;
    long queued() { return rabbit.execute(channel -> channel.queueDeclarePassive(QUEUE).getMessageCount()); }
    @Test void committedReadyVideoCannotBeDowngradedAfterLostAcknowledgement() throws Exception {
        var api=new RemainingFlowsTest(); api.http=http; var media=new MediaFlowsTest(); media.http=http;
        String user=api.user(),admin=api.admin(); byte[] video=media.video(2);
        var start=api.call(HttpMethod.POST,"/resources/videos",user,Map.of("size",video.length));
        String id=(String)start.getBody().get("id"); gate.id=id;
        var headers=new HttpHeaders(); headers.setBearerAuth(user); headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(http.exchange("/api/v1/resources/"+id+"/chunks/0",HttpMethod.PUT,new HttpEntity<>(video,headers),Map.class).getStatusCode().value()).isEqualTo(200);
        try {
            assertThat(api.call(HttpMethod.POST,"/resources/"+id+"/complete",user,null).getStatusCode().value()).isEqualTo(200);
            assertThat(gate.committed.await(30,TimeUnit.SECONDS)).as("real completion update and commit executed").isTrue();
            assertThat(api.call(HttpMethod.GET,"/resources/"+id,user,null).getBody()).containsEntry("state","READY");
            var post=api.call(HttpMethod.POST,"/posts",user,Map.of("title","视频","body","正文","resourceIds",java.util.List.of(id)));
            assertThat(post.getStatusCode().value()).isEqualTo(201);
            assertThat(api.call(HttpMethod.POST,"/admin/posts/"+post.getBody().get("id")+"/review",admin,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value()).isEqualTo(200);
            gate.release.countDown();
            assertThat(gate.firstDone.await(15,TimeUnit.SECONDS)).isTrue();
            var observed=api.call(HttpMethod.GET,"/resources/"+id,null,null).getBody();
            System.out.println("VIDEO-COMMIT after lost acknowledgement state="+observed.get("state")+" expiry="+observed.get("expiresAt"));
            assertThat(observed).containsEntry("state","READY").containsEntry("expiresAt",null);
            assertThat(media.read("/api/v1/resources/"+id+"/files/index.m3u8",null).getStatusCode().value()).isEqualTo(200);
            boolean drained=false;
            for (int attempt=0;attempt<3;attempt++) {
                registry.stop();
                if (queued()==0) { drained=true; break; }
                registry.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while (queued()>0 && System.nanoTime()<deadline) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
            assertThat(drained).as("dedicated test queue drained without deleting messages").isTrue();
            gate.duplicateDone=new CountDownLatch(1);
            int before=gate.calls.get();
            rabbit.convertAndSend(QUEUE,id);
            assertThat(queued()).as("manual duplicate is queued while consumer is stopped").isEqualTo(1);
            registry.start();
            assertThat(gate.duplicateDone.await(20,TimeUnit.SECONDS)).as("manual duplicate delivered to real consumer").isTrue();
            assertThat(gate.calls.get()).isGreaterThan(before);
            assertThat(api.call(HttpMethod.GET,"/resources/"+id,null,null).getBody()).containsEntry("state","READY").containsEntry("expiresAt",null);
        } finally { gate.release.countDown(); }
    }
}
