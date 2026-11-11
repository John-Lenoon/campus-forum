package campus;

import static org.assertj.core.api.Assertions.assertThat;
import campus.entity.ResourceEntity;
import campus.mapper.ResourceMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.aop.framework.ProxyFactory;
import org.aopalliance.intercept.MethodInterceptor;
import campus.infrastructure.ResourceStorage;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import org.apache.ibatis.annotations.Select;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.core.Ordered;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"spring.rabbitmq.listener.simple.auto-startup=false", "forum.video-poll-ms=86400000", "forum.cleanup-ms=86400000"})
@Import({Arch02ImageConsistencyTest.Fixture.class, ForumHttpTest.TimeConfig.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners=Arch02ImageConsistencyTest.Cleanup.class, mergeMode=TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class Arch02ImageConsistencyTest {
    static final String SUFFIX=java.util.UUID.randomUUID().toString().replace("-", "").substring(0,12);
    static final String DATABASE="forum_arch02_"+SUFFIX;
    static final String QUEUE="campus-forum.arch02."+SUFFIX;
    static boolean created;
    static void sql(String statement) throws Exception {
        var p=new ProcessBuilder("docker","compose","exec","-T","opengauss","su","-","omm","-c","gsql -d postgres -v ON_ERROR_STOP=1").redirectErrorStream(true).start();
        try(var out=p.getOutputStream()) { out.write((statement+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        String output=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        if (!p.waitFor(40,java.util.concurrent.TimeUnit.SECONDS) || p.exitValue()!=0) throw new IllegalStateException("test database operation failed: "+output);
    }
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry properties) throws Exception {
        if (!created) { sql("CREATE DATABASE "+DATABASE+" OWNER forum;"); created=true; }
        properties.add("spring.datasource.url",()->"jdbc:postgresql://localhost:25432/"+DATABASE);
        properties.add("forum.storage-root",()->"target/arch02-"+SUFFIX);
        properties.add("forum.video-queue",()->QUEUE);
        System.out.println("ARCH02 isolated database="+DATABASE+" queue="+QUEUE);
    }
    public static class Cleanup extends AbstractTestExecutionListener implements Ordered {
      public int getOrder() { return 0; }
      public void afterTestClass(TestContext ignored) throws Exception {
        try {
            var p=new ProcessBuilder("docker","compose","exec","-T","rabbitmq","rabbitmqctl","delete_queue",QUEUE).redirectErrorStream(true).start();
            String output=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(30,java.util.concurrent.TimeUnit.SECONDS) || (p.exitValue()!=0 && !output.contains("Queue not found")))
                throw new IllegalStateException("test queue cleanup failed: "+output);
        } finally {
            if (created) { sql("DROP DATABASE "+DATABASE+";"); created=false; }
        }
      }
    }
    @Autowired TestRestTemplate http;
    @Autowired ResourceMapper resources;
    @Autowired Fault fault;
    @Autowired ForumHttpTest.TestClock clock;
    @Autowired SqlSessionTemplate sqlSession;
    @Value("${forum.storage-root}") String storageRoot;
    RemainingFlowsTest api;
    MediaFlowsTest media;
    @BeforeEach void setup() { api=new RemainingFlowsTest(); api.http=http; media=new MediaFlowsTest(); media.http=http; fault.mode=""; fault.id=null; fault.fired=false; fault.readyWrite=false; clock.offset=0; clock.fixed=null; }
    @AfterEach void resetClock() { clock.offset=0; clock.fixed=null; }

    static class Fault {
        volatile String mode="", id; volatile boolean fired, readyWrite;
        final ThreadLocal<Boolean> upload=new ThreadLocal<>();
        final CountDownLatch stored=new CountDownLatch(1), resumeFile=new CountDownLatch(1), committing=new CountDownLatch(1), resumeCommit=new CountDownLatch(1);
    }
    interface WaitObserver {
        @Select("SELECT COUNT(*) FROM pg_stat_activity WHERE datname=current_database() AND usename=current_user AND waiting=true AND query LIKE '%forum_resources%' AND query LIKE '%FOR UPDATE%'")
        long waitingResourceLocks();
    }
    @Intercepts({
        @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}),
        @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
        @Signature(type=Executor.class,method="commit",args={boolean.class})})
    static class Injection implements Interceptor {
        final Fault f;
        Injection(Fault f) { this.f=f; }
        public Object intercept(Invocation invocation) throws Throwable {
            if (!Boolean.TRUE.equals(f.upload.get())) return invocation.proceed();
            String method=invocation.getMethod().getName();
            if (method.equals("update")) {
                MappedStatement ms=(MappedStatement)invocation.getArgs()[0];
                if (ms.getId().equals("campus.mapper.ResourceMapper.insert")) {
                    f.id=((ResourceEntity)invocation.getArgs()[1]).getId();
                    if (f.mode.equals("write")) { f.fired=true; throw new DataAccessResourceFailureException("injected write failure"); }
                    if (f.mode.equals("dbReject")) { f.fired=true; ((ResourceEntity)invocation.getArgs()[1]).setOwnerId(java.util.UUID.randomUUID().toString()); }
                }
                if (ms.getId().equals("campus.mapper.ResourceMapper.readyImage")) f.readyWrite=true;
            }
            if (method.equals("query")) {
                MappedStatement ms=(MappedStatement)invocation.getArgs()[0];
                if (f.id!=null && (f.mode.equals("read") || f.mode.equals("laterRead")) && ms.getId().equals("campus.mapper.ResourceMapper.selectById")) {
                    f.fired=true; throw new DataAccessResourceFailureException("injected read failure");
                }
            }
            if (method.equals("commit") && f.id!=null && !f.fired && f.mode.equals("beforeCommit")) {
                f.fired=true; throw new DataAccessResourceFailureException("injected commit uncertainty");
            }
            Object result=invocation.proceed();
            if (method.equals("commit") && f.id!=null && !f.fired && f.mode.equals("afterCommit")) {
                f.fired=true; throw new DataAccessResourceFailureException("injected lost commit response");
            }
            return result;
        }
    }
    @TestConfiguration static class Fixture {
        @Bean static BeanPostProcessor connectionFault(ObjectProvider<Fault> provider) {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean,String name) {
                    if (!(bean instanceof DataSource)) return bean;
                    ProxyFactory proxy=new ProxyFactory(bean); proxy.setInterfaces(DataSource.class);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        Object result=call.proceed();
                        if (!call.getMethod().getName().equals("getConnection") || !(result instanceof Connection connection)) return result;
                        return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,args)->{
                            Fault f=provider.getObject();
                            boolean commit=m.getName().equals("commit") && Boolean.TRUE.equals(f.upload.get()) && f.readyWrite && !f.fired;
                            if (commit && f.mode.equals("holdCommit")) {
                                f.committing.countDown();
                                if (!f.resumeCommit.await(30,TimeUnit.SECONDS)) throw new SQLException("commit gate timeout");
                            }
                            if (commit && f.mode.equals("physicalBefore")) { f.fired=true; throw new SQLException("injected before commit", "08006"); }
                            Object value;
                            try { value=m.invoke(connection,args); } catch (InvocationTargetException e) { throw e.getCause(); }
                            if (commit && f.mode.equals("physicalAfter")) { f.fired=true; throw new SQLException("injected lost commit acknowledgement", "08006"); }
                            return value;
                        });
                    });
                    return proxy.getProxy();
                }
            };
        }
        @Bean static BeanPostProcessor storageFault(ObjectProvider<Fault> provider) {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean,String name) {
                    if (!(bean instanceof ResourceStorage)) return bean;
                    ProxyFactory proxy=new ProxyFactory(bean);
                    proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        Object result=call.proceed();
                        Fault fault=provider.getObject();
                        if (call.getMethod().getName().equals("storeImage") && "holdFile".equals(fault.mode)) {
                            fault.stored.countDown();
                            if (!fault.resumeFile.await(30,TimeUnit.SECONDS)) throw new IOException("file gate timeout");
                        }
                        if (call.getMethod().getName().equals("storeImage") && "file".equals(fault.mode)) {
                            fault.fired=true; throw new IOException("injected interrupted file write");
                        }
                        return result;
                    });
                    return proxy.getProxy();
                }
            };
        }
        @Bean Fault imageFault() { return new Fault(); }
        @Bean Interceptor imageInjection(Fault f) { return new Injection(f); }
        @Bean OncePerRequestFilter imageRequestMarker(Fault f) {
            return new OncePerRequestFilter() {
                protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    if (req.getRequestURI().equals("/api/v1/resources/images") ||
                            ("laterRead".equals(f.mode) && req.getRequestURI().equals("/api/v1/resources/"+f.id))) f.upload.set(true);
                    try { chain.doFilter(req,res); } finally { f.upload.remove(); }
                }
            };
        }
    }
    Path file(String id) { return Path.of(storageRoot,id,"original"); }

    @Test void committedImageSurvivesPostInsertReadFailure() throws Exception {
        String user=api.user(); fault.mode="read";
        var result=media.upload(user,"POST",media.png(),"test.png");
        assertThat(fault.id).isNotNull();
        ResourceEntity row=resources.selectById(fault.id);
        assertThat(row).isNotNull(); assertThat(row.getState()).isEqualTo("READY");
        System.out.println("ARCH02 read-fault: HTTP="+result.getStatusCode().value()+", injected="+fault.fired+", state="+row.getState()+", file="+Files.isRegularFile(file(fault.id)));
        assertThat(Files.isRegularFile(file(fault.id))).as("a committed READY image retains its file").isTrue();
        if (fault.fired) assertThat(result.getStatusCode().value()).isGreaterThanOrEqualTo(500);
        else { assertThat(result.getStatusCode().value()).isEqualTo(201); assertThat(result.getBody().get("id")).isEqualTo(fault.id); }
    }

    @Test void interruptedFileWriteRemovesPartialFileBeforeDatabaseInsert() throws Exception {
        String user=api.user(); Path root=Path.of(storageRoot);
        java.util.Set<Path> before; try(var paths=Files.list(root)) { before=paths.collect(java.util.stream.Collectors.toSet()); }
        fault.mode="file";
        var response=media.upload(user,"POST",media.png(),"test.png");
        assertThat(fault.fired).isTrue(); assertThat(response.getStatusCode().value()).isEqualTo(503); assertThat(resources.selectById(fault.id).getState()).isEqualTo("UPLOADING");
        try(var paths=Files.list(root)) { assertThat(paths.collect(java.util.stream.Collectors.toSet())).isEqualTo(before); }
    }

    @Test void failedLaterReadDoesNotRemoveUploadedImage() throws Exception {
        String user=api.user();
        var upload=media.upload(user,"POST",media.png(),"test.png");
        assertThat(upload.getStatusCode().value()).isEqualTo(201);
        String id=(String)upload.getBody().get("id");
        fault.mode="laterRead";
        var failed=api.call(HttpMethod.GET,"/resources/"+id,user,null);
        assertThat(fault.fired).isTrue(); assertThat(failed.getStatusCode().value()).isEqualTo(503);
        fault.mode="";
        assertThat(resources.selectById(id).getState()).isEqualTo("READY");
        assertThat(Files.isRegularFile(file(id))).isTrue();
        assertThat(media.read((String)upload.getBody().get("url"),user).getStatusCode().value()).isEqualTo(200);
    }

    @Test void failedInsertBeforeExecutionDoesNotLeaveUntrackedFile() throws Exception {
        String user=api.user(); fault.mode="write";
        var response=media.upload(user,"POST",media.png(),"test.png");
        assertThat(fault.fired).isTrue(); assertThat(response.getStatusCode().value()).isGreaterThanOrEqualTo(500);
        assertThat(resources.selectById(fault.id)).isNull();
        assertThat(Files.exists(file(fault.id))).as("no file without a cleanup record after a confirmed pre-insert failure").isFalse();
    }

    @Test void realDatabaseRejectsInvalidForeignKeyBeforeAnyFileIsWritten() throws Exception {
        String user=api.user(); fault.mode="dbReject";
        var response=media.upload(user,"POST",media.png(),"test.png");
        assertThat(fault.fired).isTrue(); assertThat(response.getStatusCode().value()).isGreaterThanOrEqualTo(500);
        assertThat(resources.selectById(fault.id)).isNull(); assertThat(Files.exists(file(fault.id))).isFalse();
    }

    @Test void registrationAcknowledgementLossRetainsAnExpiringNonReadyRecord() throws Exception {
        String user=api.user(); fault.mode="afterCommit";
        var response=media.upload(user,"POST",media.png(),"test.png");
        assertThat(fault.fired).isTrue(); assertThat(response.getStatusCode().value()).isGreaterThanOrEqualTo(500);
        String id=fault.id; assertThat(resources.selectById(id).getState()).isEqualTo("UPLOADING");
        assertThat(Files.exists(file(id))).isFalse();
        clock.offset=25*3600000L;
        assertThat(api.call(HttpMethod.POST,"/admin/resources/cleanup",api.admin(),null).getStatusCode().value()).isEqualTo(200);
        assertThat(resources.selectById(id).getState()).isEqualTo("EXPIRED");
    }

    @Test void failedCommitBoundaryDoesNotRemovePossiblyCommittedFile() throws Exception {
        String user=api.user(), admin=api.admin();
        var bound=media.upload(user,"POST",media.png(),"bound.png").getBody();
        var post=api.call(HttpMethod.POST,"/posts",user,Map.of("title","保留","body","正文","resourceIds",java.util.List.of(bound.get("id"))));
        assertThat(post.getStatusCode().value()).isEqualTo(201);
        assertThat(api.call(HttpMethod.POST,"/admin/posts/"+post.getBody().get("id")+"/review",admin,Map.of("revision",1,"decision","APPROVE")).getStatusCode().value()).isEqualTo(200);
        fault.readyWrite=false; fault.mode="physicalBefore";
        var response=media.upload(user,"POST",media.png(),"test.png");
        assertThat(fault.fired).isTrue(); assertThat(response.getStatusCode().value()).isGreaterThanOrEqualTo(500);
        var row=resources.selectById(fault.id);
        assertThat(Files.isRegularFile(file(fault.id))).isTrue();
        assertThat(row.getState()).isEqualTo("UPLOADING");
        assertThat(api.call(HttpMethod.GET,"/resources/"+fault.id,user,null).getStatusCode().value()).isEqualTo(404);
        String id=fault.id; clock.offset=25*3600000L;
        assertThat(api.call(HttpMethod.POST,"/admin/resources/cleanup",api.admin(),null).getStatusCode().value()).isEqualTo(200);
        assertThat(resources.selectById(id).getState()).isEqualTo("EXPIRED"); assertThat(Files.exists(file(id))).isFalse();
        assertThat(Files.isRegularFile(file((String)bound.get("id")))).isTrue();
        assertThat(media.read((String)bound.get("url"),null).getStatusCode().value()).isEqualTo(200);
    }

    @Test void lostCommitResponseKeepsCommittedFileAndAllowsBinding() throws Exception {
        String user=api.user(), admin=api.admin(); fault.mode="physicalAfter";
        var response=media.upload(user,"POST",media.png(),"test.png");
        assertThat(fault.fired).isTrue(); assertThat(response.getStatusCode().value()).isGreaterThanOrEqualTo(500);
        assertThat(resources.selectById(fault.id).getState()).isEqualTo("READY");
        assertThat(Files.isRegularFile(file(fault.id))).isTrue();
        var post=api.call(HttpMethod.POST,"/posts",user,Map.of("title","图片","body","正文","resourceIds",java.util.List.of(fault.id)));
        assertThat(post.getStatusCode().value()).isEqualTo(201);
        api.call(HttpMethod.POST,"/admin/posts/"+post.getBody().get("id")+"/review",admin,Map.of("revision",1,"decision","APPROVE"));
        assertThat(media.read("/api/v1/resources/"+fault.id+"/files/original",null).getStatusCode().value()).isEqualTo(200);
    }

    @Test void invalidImageIsRemovedBeforeAnyDatabaseWrite() throws Exception {
        String user=api.user(); Path root=Path.of(storageRoot);
        java.util.Set<Path> before; try(var paths=Files.list(root)) { before=paths.collect(java.util.stream.Collectors.toSet()); }
        var response=media.upload(user,"POST","invalid".getBytes(),"bad.png");
        assertThat(response.getStatusCode().value()).isEqualTo(400); assertThat(resources.selectById(fault.id).getState()).isEqualTo("UPLOADING");
        try(var paths=Files.list(root)) { assertThat(paths.collect(java.util.stream.Collectors.toSet())).isEqualTo(before); }
    }

    @Test void avatarAndImageRemainUsableAfterSuccessfulUpload() throws Exception {
        String user=api.user(), admin=api.admin();
        var image=media.upload(user,"POST",media.png(),"image.png"); assertThat(image.getStatusCode().value()).isEqualTo(201);
        var post=api.call(HttpMethod.POST,"/posts",user,Map.of("title","图片","body","正文","resourceIds",java.util.List.of(image.getBody().get("id"))));
        assertThat(post.getStatusCode().value()).isEqualTo(201);
        api.call(HttpMethod.POST,"/admin/posts/"+post.getBody().get("id")+"/review",admin,Map.of("revision",1,"decision","APPROVE"));
        assertThat(media.read((String)image.getBody().get("url"),null).getStatusCode().value()).isEqualTo(200);
        var avatar=media.upload(user,"AVATAR",media.png(),"avatar.png"); assertThat(avatar.getStatusCode().value()).isEqualTo(201);
        assertThat(api.call(HttpMethod.PATCH,"/me",user,Map.of("avatarResourceId",avatar.getBody().get("id"))).getStatusCode().value()).isEqualTo(200);
        assertThat(media.read((String)avatar.getBody().get("url"),null).getStatusCode().value()).isEqualTo(200);
        assertThat(media.read((String)image.getBody().get("url"),null).getStatusCode().value()).isEqualTo(200);
    }

    @Test void expirySweepWaitsForImageFinalizationAndKeepsRenewedFile() throws Exception {
        String user=api.user(); fault.mode="holdFile";
        if (!sqlSession.getConfiguration().hasMapper(WaitObserver.class)) sqlSession.getConfiguration().addMapper(WaitObserver.class);
        var observer=sqlSession.getMapper(WaitObserver.class);
        try(var pool=Executors.newFixedThreadPool(2)) {
            try {
                var upload=pool.submit(()->media.upload(user,"POST",media.png(),"locked.png"));
                assertThat(fault.stored.await(15,TimeUnit.SECONDS)).isTrue();
                String id=fault.id; assertThat(Files.isRegularFile(file(id))).isTrue();
                clock.offset=23*3600000L; fault.mode="holdCommit"; fault.resumeFile.countDown();
                assertThat(fault.committing.await(15,TimeUnit.SECONDS)).isTrue();
                clock.offset=25*3600000L; String admin=api.admin();
                var cleanup=pool.submit(()->api.call(HttpMethod.POST,"/admin/resources/cleanup",admin,null));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
                while (observer.waitingResourceLocks()==0 && System.nanoTime()<deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                assertThat(observer.waitingResourceLocks()).as("cleanup waits for the resource row").isGreaterThan(0);
                fault.resumeCommit.countDown();
                assertThat(upload.get(15,TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(201);
                assertThat(cleanup.get(15,TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
                assertThat(resources.selectById(id).getState()).isEqualTo("READY");
                assertThat(Files.isRegularFile(file(id))).isTrue();
                clock.offset=0;
                assertThat(media.read("/api/v1/resources/"+id+"/files/original",user).getStatusCode().value()).isEqualTo(200);
            } finally { fault.resumeFile.countDown(); fault.resumeCommit.countDown(); }
        }
    }
}
