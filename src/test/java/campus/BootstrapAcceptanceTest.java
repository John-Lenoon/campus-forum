package campus;

import org.junit.jupiter.api.Test;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class BootstrapAcceptanceTest {
    static class Gate implements ApplicationRunner,Ordered {
        final ConfigurableApplicationContext context;final CountDownLatch entered,release;final AtomicInteger port;
        Gate(ConfigurableApplicationContext context,CountDownLatch entered,CountDownLatch release,AtomicInteger port){this.context=context;this.entered=entered;this.release=release;this.port=port;}
        public int getOrder(){return -50;}
        public void run(ApplicationArguments args)throws Exception{
            port.set(((ServletWebServerApplicationContext)context).getWebServer().getPort());entered.countDown();
            if(!release.await(60,TimeUnit.SECONDS))throw new IllegalStateException("Test startup gate timed out");
        }
    }
    void sql(String sql)throws Exception{
        var process=new ProcessBuilder("docker","compose","exec","-T","opengauss","su","-","omm","-c","gsql -d postgres -v ON_ERROR_STOP=1").redirectErrorStream(true).start();
        try(var input=process.getOutputStream()){input.write((sql+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();String output=new String(process.getInputStream().readAllBytes());assertThat(process.exitValue()).as(output).isZero();
    }
    @Test void presetAdminExistsBeforePublicRegistrationCanReachTheServer()throws Exception{
        String database="forum_bootstrap_"+UUID.randomUUID().toString().replace("-", "").substring(0,10);
        sql("CREATE DATABASE "+database+" OWNER forum;");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var port=new AtomicInteger();
        ConfigurableApplicationContext app=null;
        try(var pool=Executors.newSingleThreadExecutor()){
            var future=pool.submit(()->new SpringApplicationBuilder(ForumApplication.class)
                    .initializers(context->context.getBeanFactory().registerSingleton("acceptanceStartupGate",new Gate(context,entered,release,port)))
                    .run("--server.port=0","--spring.datasource.url=jdbc:postgresql://localhost:25432/"+database,"--forum.storage-root=target/bootstrap-media","--logging.level.root=ERROR"));
            try{
                assertThat(entered.await(45,TimeUnit.SECONDS)).isTrue();String base="http://127.0.0.1:"+port.get()+"/api/v1";var client=new TestRestTemplate();
                var registration=client.postForEntity(base+"/auth/register",Map.of("username","admin","password","password123"),Map.class);
                assertThat(registration.getStatusCode().value()).isEqualTo(409);
                release.countDown();app=future.get(45,TimeUnit.SECONDS);
                var login=client.postForEntity(base+"/auth/login",Map.of("username","admin","password","admin123"),Map.class);
                assertThat(login.getStatusCode().value()).isEqualTo(200);assertThat((Map)login.getBody().get("user")).containsEntry("role","ADMIN");
            }finally{
                release.countDown();if(app==null)try{app=future.get(45,TimeUnit.SECONDS);}catch(Exception ignored){}
                if(app!=null){try{app.getBean(AmqpAdmin.class).deleteQueue(app.getBean(Queue.class).getName());}finally{app.close();}}
            }
        }finally{sql("DROP DATABASE "+database+";");}
    }
}
