package campus;

import static org.assertj.core.api.Assertions.assertThat;
import campus.service.VideoService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.AbstractTestExecutionListener;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Arch03ProcessInterruptionTest.Fixture.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners=Arch03ProcessInterruptionTest.Cleanup.class,mergeMode=TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class Arch03ProcessInterruptionTest {
    static final String SUFFIX=java.util.UUID.randomUUID().toString().replace("-", "").substring(0,12);
    static final String DATABASE="forum_arch03_"+SUFFIX, QUEUE="campus-forum.arch03."+SUFFIX;
    static boolean created;
    static void sql(String statement) throws Exception {
        var p=new ProcessBuilder("docker","compose","exec","-T","opengauss","su","-","omm","-c","gsql -d postgres -v ON_ERROR_STOP=1").redirectErrorStream(true).start();
        try(var out=p.getOutputStream()) { out.write((statement+"\n").getBytes(StandardCharsets.UTF_8)); }
        String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if (!p.waitFor(40,TimeUnit.SECONDS) || p.exitValue()!=0) throw new IllegalStateException(output);
    }
    @DynamicPropertySource static void isolation(DynamicPropertyRegistry p) throws Exception {
        if (!created) { sql("CREATE DATABASE "+DATABASE+" OWNER forum;"); created=true; }
        p.add("spring.datasource.url",()->"jdbc:postgresql://localhost:25432/"+DATABASE);
        p.add("forum.storage-root",()->"target/arch03-"+SUFFIX);
        p.add("forum.video-queue",()->QUEUE);
        System.out.println("ARCH03 isolated database="+DATABASE+" queue="+QUEUE);
    }
    public static class Cleanup extends AbstractTestExecutionListener implements Ordered {
        public int getOrder() { return 0; }
        public void afterTestClass(TestContext ignored) throws Exception {
            try {
                var p=new ProcessBuilder("docker","compose","exec","-T","rabbitmq","rabbitmqctl","delete_queue",QUEUE).redirectErrorStream(true).start();
                String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                if (!p.waitFor(30,TimeUnit.SECONDS) || (p.exitValue()!=0 && !out.contains("Queue not found"))) throw new IllegalStateException(out);
            } finally { if(created) { sql("DROP DATABASE "+DATABASE+";"); created=false; } }
        }
    }
    static class Capture { volatile String id; volatile Thread worker; final CountDownLatch entered=new CountDownLatch(1), finished=new CountDownLatch(1); }
    @TestConfiguration static class Fixture {
        @Bean Capture capture() { return new Capture(); }
        @Bean static BeanPostProcessor captureVideoWorker(ObjectProvider<Capture> provider) {
            return new BeanPostProcessor() {
                public Object postProcessAfterInitialization(Object bean,String name) {
                    if (!(bean instanceof VideoService)) return bean;
                    ProxyFactory proxy=new ProxyFactory(bean); proxy.setProxyTargetClass(true);
                    proxy.addAdvice((MethodInterceptor) call -> {
                        Capture c=provider.getObject();
                        if (!call.getMethod().getName().equals("process") || !java.util.Objects.equals(c.id,call.getArguments()[0])) return call.proceed();
                        c.worker=Thread.currentThread(); c.entered.countDown();
                        try { return call.proceed(); } finally { c.finished.countDown(); }
                    });
                    return proxy.getProxy();
                }
            };
        }
    }
    @Autowired TestRestTemplate http;
    @Autowired Capture capture;
    @Value("${forum.storage-root}") String storageRoot;

    ProcessHandle findTranscoder(String id) throws Exception {
        var children=ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).toList();
        for (var p:children) {
            String command=p.info().commandLine().orElse("");
            if (command.contains(id) && command.toLowerCase().contains("ffmpeg")) return p;
        }
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) return null;
        if (!id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("invalid resource id");
        String script="Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'ffmpeg.exe' -and $_.CommandLine -like '*"+id+"*' } | ForEach-Object { $_.ProcessId }";
        var probe=new ProcessBuilder("powershell","-NoProfile","-Command",script).redirectErrorStream(true).start();
        String output=new String(probe.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        if (!probe.waitFor(10,TimeUnit.SECONDS) || probe.exitValue()!=0) throw new IllegalStateException("process identity query failed: "+output);
        System.out.println("ARCH03 process identity candidates="+output+" descendants="+children.stream().map(ProcessHandle::pid).toList());
        for (String line:output.split("\\R")) if (line.trim().matches("[0-9]+")) {
            long pid=Long.parseLong(line.trim());
            for (var p:children) if (p.pid()==pid && p.isAlive()) return p;
        }
        return null;
    }

    byte[] longVideo() throws Exception {
        String ffmpeg;
        try(var paths=Files.walk(Path.of(".tools"),4)) { ffmpeg=paths.filter(p->p.getFileName().toString().equals("ffmpeg.exe")).findFirst().orElseThrow().toAbsolutePath().toString(); }
        Path source=Path.of("target","arch03-source-"+SUFFIX+".mp4");
        Process p=null;
        try {
            p=new ProcessBuilder(ffmpeg,"-v","error","-y","-f","lavfi","-i","color=c=blue:s=1280x720:r=30","-t","120","-c:v","libx264","-preset","ultrafast","-pix_fmt","yuv420p","-movflags","+faststart",source.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(p.waitFor(60,TimeUnit.SECONDS)).isTrue(); assertThat(p.exitValue()).isZero();
            return Files.readAllBytes(source);
        } finally {
            if (p!=null && p.isAlive()) { p.destroyForcibly(); p.waitFor(15,TimeUnit.SECONDS); }
            Files.deleteIfExists(source);
        }
    }

    @Test void interruptedRealTranscodeStopsProcessAndDoesNotPublishPartialVideo() throws Exception {
        var api=new RemainingFlowsTest(); api.http=http;
        var media=new MediaFlowsTest(); media.http=http;
        String user=api.user(); byte[] bytes=longVideo();
        var started=api.call(HttpMethod.POST,"/resources/videos",user,Map.of("size",bytes.length));
        assertThat(started.getStatusCode().value()).isEqualTo(201);
        String id=(String)started.getBody().get("id"); capture.id=id;
        var headers=new HttpHeaders(); headers.setBearerAuth(user); headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        int chunk=((Number)started.getBody().get("chunkSize")).intValue();
        for(int offset=0;offset<bytes.length;offset+=chunk)
            assertThat(http.exchange("/api/v1/resources/"+id+"/chunks/"+(offset/chunk),HttpMethod.PUT,new HttpEntity<>(java.util.Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+chunk)),headers),Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(api.call(HttpMethod.POST,"/resources/"+id+"/complete",user,null).getStatusCode().value()).isEqualTo(200);
        assertThat(capture.entered.await(30,TimeUnit.SECONDS)).as("actual MQ worker claimed this resource").isTrue();
        ProcessHandle child=null;
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime()<deadline) {
                boolean waiting=java.util.Arrays.stream(capture.worker.getStackTrace()).anyMatch(frame->frame.getClassName().equals("campus.infrastructure.MediaTools") && frame.getMethodName().equals("hls"));
                if (waiting && (child==null || !child.isAlive())) child=findTranscoder(id);
                Path dir=Path.of(storageRoot,id);
                boolean output=false;
                if (Files.isDirectory(dir)) try(var paths=Files.walk(dir,2)) { output=paths.anyMatch(p->p.getFileName().toString().endsWith(".ts")); }
                if (child!=null && waiting && output) break;
                if (capture.finished.getCount()==0) break;
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
            if (child==null) System.out.println("ARCH03 missing process state="+api.call(HttpMethod.GET,"/resources/"+id,user,null).getBody()+" workerFinished="+(capture.finished.getCount()==0));
            assertThat(child).as("live real ffmpeg with this resource UUID").isNotNull();
            assertThat(child.isAlive()).isTrue();
            assertThat(java.util.Arrays.stream(capture.worker.getStackTrace()).anyMatch(f->f.getClassName().equals("campus.infrastructure.MediaTools") && f.getMethodName().equals("hls"))).as("worker waiting in hls").isTrue();
            Path dir=Path.of(storageRoot,id);
            try(var paths=Files.walk(dir,2)) { assertThat(paths.anyMatch(p->p.getFileName().toString().endsWith(".ts"))).as("ffmpeg created segment output").isTrue(); }
            capture.worker.interrupt();
            boolean exited;
            try { child.onExit().get(3,TimeUnit.SECONDS); exited=true; } catch (java.util.concurrent.TimeoutException e) { exited=false; }
            assertThat(capture.finished.await(15,TimeUnit.SECONDS)).as("actual worker completed").isTrue();
            var result=api.call(HttpMethod.GET,"/resources/"+id,user,null);
            System.out.println("ARCH03 interruption processExited="+exited+" state="+result.getBody().get("state"));
            final boolean childExited=exited;
            org.assertj.core.api.SoftAssertions.assertSoftly(soft->{
                soft.assertThat(childExited).as("real ffmpeg exits on interruption").isTrue();
                soft.assertThat(result.getBody()).containsEntry("state","FAILED");
            });
            Map<String,Long> sizesBefore;
            try(var paths=Files.walk(dir,2)) {
                sizesBefore=paths.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toMap(Path::toString,p->{try { return Files.size(p); } catch(Exception e) { throw new IllegalStateException(e); }}));
            }
            Thread.sleep(500);
            try(var paths=Files.walk(dir,2)) {
                var sizesAfter=paths.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toMap(Path::toString,p->{try { return Files.size(p); } catch(Exception e) { throw new IllegalStateException(e); }}));
                assertThat(sizesAfter).as("interrupted process no longer writes files").isEqualTo(sizesBefore);
            }
            assertThat(api.call(HttpMethod.POST,"/posts",user,Map.of("title","中断","body","正文","resourceIds",java.util.List.of(id))).getStatusCode().value()).isEqualTo(400);
        } finally {
            if(child!=null && child.isAlive()) {
                var verified=findTranscoder(id);
                assertThat(verified).as("only terminate this test's verified child").isNotNull();
                assertThat(verified.pid()).isEqualTo(child.pid());
                child.destroyForcibly(); child.onExit().get(15,TimeUnit.SECONDS);
            }
        }
    }
}
