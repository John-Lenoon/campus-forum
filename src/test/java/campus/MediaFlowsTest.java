package campus;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="spring.datasource.url=jdbc:postgresql://localhost:25432/forum_test")
@Import(ForumHttpTest.TimeConfig.class)
class MediaFlowsTest {
    @Autowired TestRestTemplate http;
    @Autowired ForumHttpTest.TestClock clock;
    @Value("${forum.storage-root}")String storageRoot;
    RemainingFlowsTest f;
    @BeforeEach void setup(){clock.offset=0;clock.fixed=null;f=new RemainingFlowsTest();f.http=http;}
    @AfterEach void reset(){clock.offset=0;clock.fixed=null;}
    byte[] png()throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    ResponseEntity<Map> upload(String token,String purpose,byte[] bytes,String name){
        var form=new LinkedMultiValueMap<String,Object>();form.add("file",new ByteArrayResource(bytes){@Override public String getFilename(){return name;}});
        var h=new HttpHeaders();h.setBearerAuth(token);h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/api/v1/resources/images?purpose="+purpose,HttpMethod.POST,new HttpEntity<>(form,h),Map.class);
    }
    ResponseEntity<byte[]> read(String path,String token){var h=new HttpHeaders();if(token!=null)h.setBearerAuth(token);return http.exchange(path,HttpMethod.GET,new HttpEntity<>(h),byte[].class);}
    @Test void avatarReplacementIsImmediatePublicAndOldResourceCannotBeReused()throws Exception{
        String u=f.user();var first=upload(u,"AVATAR",png(),"avatar.png").getBody();
        var changed=f.call(HttpMethod.PATCH,"/me",u,Map.of("avatarResourceId",first.get("id")));
        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        assertThat(changed.getBody()).containsEntry("avatarUrl",first.get("url"));
        assertThat(read((String)first.get("url"),null).getStatusCode().value()).isEqualTo(200);
        var second=upload(u,"AVATAR",png(),"avatar.png").getBody();
        assertThat(f.call(HttpMethod.PATCH,"/me",u,Map.of("avatarResourceId",second.get("id"))).getStatusCode().value()).isEqualTo(200);
        assertThat(read((String)first.get("url"),u).getStatusCode().value()).isEqualTo(404);
        assertThat(f.call(HttpMethod.PATCH,"/me",u,Map.of("avatarResourceId",first.get("id"))).getStatusCode().value()).isEqualTo(400);
        assertThat(f.call(HttpMethod.POST,"/posts",u,Map.of("title","无效","body","正文","resourceIds",List.of(second.get("id")))).getStatusCode().value()).isEqualTo(400);
        String id=(String)changed.getBody().get("id");
        assertThat(f.call(HttpMethod.GET,"/users/"+id,u,null).getBody()).containsEntry("avatarUrl",second.get("url"));
    }
    byte[] video(int seconds)throws Exception{
        String ffmpeg;
        try(var paths=Files.walk(Path.of(".tools"),4)){ffmpeg=paths.filter(p->p.getFileName().toString().equals("ffmpeg.exe")).findFirst().orElseThrow().toAbsolutePath().toString();}
        Path file=Files.createTempFile(Path.of("target"),"media-fixture-",".mp4");
        var process=new ProcessBuilder(ffmpeg,"-v","error","-y","-f","lavfi","-i","color=c=blue:s=160x90:r=2","-t",String.valueOf(seconds),"-c:v","libx264","-pix_fmt","yuv420p","-movflags","+faststart",file.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(process.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        byte[] bytes=Files.readAllBytes(file);Files.delete(file);return bytes;
    }
    byte[] fixture(String suffix,String... options)throws Exception{
        String executable=System.getenv("FFMPEG_PATH");
        if(executable==null){try(var paths=Files.walk(Path.of(".tools"),4)){executable=paths.filter(p->p.getFileName().toString().equals("ffmpeg.exe")).findFirst().orElseThrow().toAbsolutePath().toString();}}
        Path file=Files.createTempFile(Path.of("target"),"media-format-",suffix);
        var command=new ArrayList<>(List.of(executable,"-v","error","-y","-f","lavfi","-i","testsrc2=s=32x32:r=2"));command.addAll(List.of(options));command.add(file.toString());
        var process=new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();assertThat(process.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        byte[] bytes=Files.readAllBytes(file);Files.delete(file);return bytes;
    }
    Map uploadVideo(String u,byte[] bytes)throws Exception{
        var start=f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",bytes.length));assertThat(start.getStatusCode().value()).isEqualTo(201);
        String id=(String)start.getBody().get("id");int chunk=((Number)start.getBody().get("chunkSize")).intValue();
        var h=new HttpHeaders();h.setBearerAuth(u);h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        for(int i=0;i<bytes.length;i+=chunk)assertThat(http.exchange("/api/v1/resources/"+id+"/chunks/"+(i/chunk),HttpMethod.PUT,new HttpEntity<>(Arrays.copyOfRange(bytes,i,Math.min(bytes.length,i+chunk)),h),Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(f.call(HttpMethod.POST,"/resources/"+id+"/complete",u,null).getStatusCode().value()).isEqualTo(200);
        Map result=null;for(int i=0;i<240;i++){result=f.call(HttpMethod.GET,"/resources/"+id,u,null).getBody();if(!"PROCESSING".equals(result.get("state")))return result;Thread.sleep(500);}throw new AssertionError("Processing did not finish: "+result);
    }
    @Test void imageFormatsSizeLimitsAndNineImageBindingAreEnforced()throws Exception{
        String u=f.user();byte[] png=png();
        assertThat(upload(u,"POST",Arrays.copyOf(png,10*1024*1024),"limit.png").getStatusCode().value()).isEqualTo(201);
        assertThat(upload(u,"POST",Arrays.copyOf(png,10*1024*1024+1),"over.png").getStatusCode().value()).isEqualTo(400);
        assertThat(upload(u,"AVATAR",Arrays.copyOf(png,5*1024*1024),"limit.png").getStatusCode().value()).isEqualTo(201);
        assertThat(upload(u,"AVATAR",Arrays.copyOf(png,5*1024*1024+1),"over.png").getStatusCode().value()).isEqualTo(400);
        assertThat(upload(u,"POST","not an image".getBytes(),"fake.png").getStatusCode().value()).isEqualTo(400);
        byte[] jpeg=fixture(".jpg","-frames:v","1","-c:v","mjpeg");byte[] webp=fixture(".webp","-frames:v","1","-c:v","libwebp");
        assertThat(upload(u,"POST",jpeg,"actual.jpg").getStatusCode().value()).isEqualTo(201);
        assertThat(upload(u,"POST",webp,"actual.webp").getStatusCode().value()).isEqualTo(201);
        byte[] animated=fixture(".webp","-t","1","-c:v","libwebp_anim","-loop","0");
        assertThat(upload(u,"POST",animated,"animated.webp").getStatusCode().value()).isEqualTo(201);
        assertThat(upload(u,"AVATAR",animated,"animated.webp").getStatusCode().value()).isEqualTo(400);
        var ids=new ArrayList<Object>();for(int i=0;i<10;i++){var r=upload(u,"POST",png,"image.png");assertThat(r.getStatusCode().value()).isEqualTo(201);ids.add(r.getBody().get("id"));}
        assertThat(f.call(HttpMethod.POST,"/posts",u,Map.of("title","超量","body","正文","resourceIds",ids)).getStatusCode().value()).isEqualTo(400);
        var accepted=f.call(HttpMethod.POST,"/posts",u,Map.of("title","九张","body","正文","resourceIds",ids.subList(0,9)));
        assertThat(accepted.getStatusCode().value()).isEqualTo(201);assertThat((List)accepted.getBody().get("attachments")).hasSize(9);
        assertThat(f.call(HttpMethod.POST,"/posts",u,Map.of("title","跨帖复用","body","正文","resourceIds",List.of(ids.getFirst()))).getStatusCode().value()).isEqualTo(400);
    }
    @Test void videoDurationAndByteBoundariesAndFailedProcessingAreObservable()throws Exception{
        String u=f.user();
        assertThat(f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",1073741824L)).getStatusCode().value()).isEqualTo(201);
        for(long size:new long[]{0,-1,1073741825L})assertThat(f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",size)).getStatusCode().value()).isEqualTo(400);
        var invalid=uploadVideo(u,png());assertThat(invalid).containsEntry("state","FAILED");
        assertThat(f.call(HttpMethod.POST,"/posts",u,Map.of("title","失败资源","body","正文","resourceIds",List.of(invalid.get("id")))).getStatusCode().value()).isEqualTo(400);
        var tooLong=uploadVideo(u,video(1801));assertThat(tooLong).containsEntry("state","FAILED").containsEntry("errorCode","VIDEO_DURATION_LIMIT");
        var maximum=uploadVideo(u,video(1800));assertThat(maximum).containsEntry("state","READY");assertThat(((Number)maximum.get("duration")).doubleValue()).isEqualTo(1800.0);
        var image=upload(u,"POST",png(),"mixed.png").getBody();
        assertThat(f.call(HttpMethod.POST,"/posts",u,Map.of("title","不能混合","body","正文","resourceIds",List.of(maximum.get("id"),image.get("id")))).getStatusCode().value()).isEqualTo(400);
        var another=uploadVideo(u,video(1));
        assertThat(f.call(HttpMethod.POST,"/posts",u,Map.of("title","两个视频","body","正文","resourceIds",List.of(maximum.get("id"),another.get("id")))).getStatusCode().value()).isEqualTo(400);
        String ffmpeg;try(var paths=Files.walk(Path.of(".tools"),4)){ffmpeg=paths.filter(p->p.getFileName().toString().equals("ffmpeg.exe")).findFirst().orElseThrow().toAbsolutePath().toString();}
        var decode=new ProcessBuilder(ffmpeg,"-nostdin","-v","error","-headers","Authorization: Bearer "+u+"\r\n","-i",http.getRootUri()+maximum.get("url"),"-f","null","-").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        assertThat(decode.waitFor(90,TimeUnit.SECONDS)).isTrue();assertThat(decode.exitValue()).isZero();
    }
    @Test void multipleVideoPartsAllowOutOfOrderResumeAndDuplicateSuccessfulParts()throws Exception{
        String u=f.user();byte[] base=video(1);byte[] bytes=Arrays.copyOf(base,8389632);
        java.nio.ByteBuffer.wrap(bytes,base.length,4).putInt(bytes.length-base.length);System.arraycopy("free".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,bytes,base.length+4,4);
        var start=f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",bytes.length));assertThat(start.getStatusCode().value()).isEqualTo(201);String id=(String)start.getBody().get("id");
        var h=new HttpHeaders();h.setBearerAuth(u);h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(http.exchange("/api/v1/resources/"+id+"/chunks/1",HttpMethod.PUT,new HttpEntity<>(Arrays.copyOfRange(bytes,8388608,bytes.length),h),Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat(f.call(HttpMethod.POST,"/resources/"+id+"/complete",u,null).getStatusCode().value()).isEqualTo(400);
        assertThat((List)f.call(HttpMethod.GET,"/resources/"+id,u,null).getBody().get("uploadedParts")).containsExactly(1);
        for(int retry=0;retry<2;retry++)assertThat(http.exchange("/api/v1/resources/"+id+"/chunks/0",HttpMethod.PUT,new HttpEntity<>(Arrays.copyOfRange(bytes,0,8388608),h),Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat((List)f.call(HttpMethod.GET,"/resources/"+id,u,null).getBody().get("uploadedParts")).containsExactly(0,1);
        f.call(HttpMethod.POST,"/resources/"+id+"/complete",u,null);
        Map result=null;for(int i=0;i<120;i++){result=f.call(HttpMethod.GET,"/resources/"+id,u,null).getBody();if(!"PROCESSING".equals(result.get("state")))break;Thread.sleep(500);}
        assertThat(result).containsEntry("state","READY");
    }
    @Test void imageTypeIsValidatedFromContainerNotFilenameOrVideoCodec()throws Exception{
        String u=f.user();byte[] disguised=fixture(".avi","-t","1","-c:v","mjpeg");
        assertThat(upload(u,"POST",disguised,"fake.jpg").getStatusCode().value()).isEqualTo(400);
    }
    @Test void failedAndRetiredResourcesArePhysicallyReclaimedOnlyAfterTheirDeadline()throws Exception{
        String name="expiry"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        f.call(HttpMethod.POST,"/auth/register",null,Map.of("username",name,"password","password123"));
        String u=(String)f.call(HttpMethod.POST,"/auth/login",null,Map.of("username",name,"password","password123")).getBody().get("token");String a=f.admin();
        var old=upload(u,"AVATAR",png(),"old.png").getBody();f.call(HttpMethod.PATCH,"/me",u,Map.of("avatarResourceId",old.get("id")));
        var current=upload(u,"AVATAR",png(),"current.png").getBody();f.call(HttpMethod.PATCH,"/me",u,Map.of("avatarResourceId",current.get("id")));
        var removed=upload(u,"POST",png(),"removed.png").getBody();
        var p=f.call(HttpMethod.POST,"/posts",u,Map.of("title","解绑","body","正文","resourceIds",List.of(removed.get("id")))).getBody();
        f.call(HttpMethod.PUT,"/posts/"+p.get("id"),u,Map.of("revision",1,"title","纯文字","body","正文","resourceIds",List.of()));
        var active=upload(u,"POST",png(),"active.png").getBody();
        var valid=f.call(HttpMethod.POST,"/posts",u,Map.of("title","有效绑定","body","正文","resourceIds",List.of(active.get("id")))).getBody();
        f.call(HttpMethod.POST,"/admin/posts/"+valid.get("id")+"/review",a,Map.of("revision",1,"decision","APPROVE"));
        var idle=f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",2)).getBody();
        var failed=uploadVideo(u,png());assertThat(failed).containsEntry("state","FAILED");long deadline=((Number)failed.get("expiresAt")).longValue();
        assertThat(deadline-System.currentTimeMillis()).isBetween(86390000L,86401000L);
        Path root=Path.of(storageRoot);assertThat(Files.isDirectory(root.resolve((String)old.get("id")))).isTrue();
        clock.offset=23*3600000L;
        String renewed=(String)f.call(HttpMethod.POST,"/auth/login",null,Map.of("username",name,"password","password123")).getBody().get("token");
        var h=new HttpHeaders();h.setBearerAuth(renewed);h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(http.exchange("/api/v1/resources/"+idle.get("id")+"/chunks/0",HttpMethod.PUT,new HttpEntity<>(new byte[]{1},h),Map.class).getStatusCode().value()).isEqualTo(400);
        f.call(HttpMethod.POST,"/admin/resources/cleanup",f.admin(),null);
        assertThat(Files.isDirectory(root.resolve((String)old.get("id")))).isTrue();assertThat(Files.isDirectory(root.resolve((String)removed.get("id")))).isTrue();
        clock.offset=0;clock.fixed=deadline-1;
        assertThat(f.call(HttpMethod.GET,"/resources/"+failed.get("id"),renewed,null).getStatusCode().value()).isEqualTo(200);
        clock.fixed=deadline;
        assertThat(f.call(HttpMethod.GET,"/resources/"+failed.get("id"),renewed,null).getStatusCode().value()).isEqualTo(410);
        assertThat(f.call(HttpMethod.GET,"/resources/"+idle.get("id"),renewed,null).getStatusCode().value()).isEqualTo(410);
        assertThat(f.call(HttpMethod.POST,"/admin/resources/cleanup",f.admin(),null).getStatusCode().value()).isEqualTo(200);
        for(var r:List.of(old,removed,failed,idle))assertThat(Files.exists(root.resolve((String)r.get("id")))).isFalse();
        assertThat(Files.isDirectory(root.resolve((String)current.get("id")))).isTrue();
        assertThat(read((String)current.get("url"),null).getStatusCode().value()).isEqualTo(200);assertThat(read((String)active.get("url"),null).getStatusCode().value()).isEqualTo(200);
    }
    @Test void processingHasNoTemporaryExpiryAndDatabaseJobRecoversAfterRabbitRestart()throws Exception{
        String name="mq"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        f.call(HttpMethod.POST,"/auth/register",null,Map.of("username",name,"password","password123"));
        String u=(String)f.call(HttpMethod.POST,"/auth/login",null,Map.of("username",name,"password","password123")).getBody().get("token");
        byte[] bytes=video(1);var init=f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",bytes.length));String id=(String)init.getBody().get("id");
        var h=new HttpHeaders();h.setBearerAuth(u);h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(http.exchange("/api/v1/resources/"+id+"/chunks/0",HttpMethod.PUT,new HttpEntity<>(bytes,h),Map.class).getStatusCode().value()).isEqualTo(200);
        var stop=new ProcessBuilder("docker","compose","stop","rabbitmq").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();assertThat(stop.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(stop.exitValue()).isZero();
        try{
            assertThat(f.call(HttpMethod.POST,"/resources/"+id+"/complete",u,null).getStatusCode().value()).isEqualTo(200);
            assertThat(f.call(HttpMethod.GET,"/resources/"+id,u,null).getBody()).containsEntry("state","PROCESSING").containsEntry("expiresAt",null);
            assertThat(f.call(HttpMethod.POST,"/posts",u,Map.of("title","处理中","body","正文","resourceIds",List.of(id))).getStatusCode().value()).isEqualTo(400);
            clock.offset=25*3600000L;
            String renewed=(String)f.call(HttpMethod.POST,"/auth/login",null,Map.of("username",name,"password","password123")).getBody().get("token");
            f.call(HttpMethod.POST,"/admin/resources/cleanup",f.admin(),null);
            assertThat(f.call(HttpMethod.GET,"/resources/"+id,renewed,null).getBody()).containsEntry("state","PROCESSING");
        }finally{
            clock.offset=0;var start=new ProcessBuilder("docker","compose","start","rabbitmq").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();assertThat(start.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(start.exitValue()).isZero();
        }
        Map ready=null;for(int i=0;i<240;i++){ready=f.call(HttpMethod.GET,"/resources/"+id,u,null).getBody();if(!"PROCESSING".equals(ready.get("state")))break;Thread.sleep(500);}
        assertThat(ready).containsEntry("state","READY");
    }
    @Test @Tag("large-media") void actualOneGiBVideoUploadsWithBoundedChunksAndIsCleanedAfterExpiry()throws Exception{
        String name="large"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        f.call(HttpMethod.POST,"/auth/register",null,Map.of("username",name,"password","password123"));
        String u=(String)f.call(HttpMethod.POST,"/auth/login",null,Map.of("username",name,"password","password123")).getBody().get("token");
        byte[] base=video(1);byte[] first=new byte[8388608];System.arraycopy(base,0,first,0,base.length);
        java.nio.ByteBuffer.wrap(first,base.length,4).putInt(1073741824-base.length);System.arraycopy("free".getBytes(java.nio.charset.StandardCharsets.US_ASCII),0,first,base.length+4,4);
        var start=f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",1073741824L));assertThat(start.getStatusCode().value()).isEqualTo(201);String id=(String)start.getBody().get("id");
        try{
            var h=new HttpHeaders();h.setBearerAuth(u);h.setContentType(MediaType.APPLICATION_OCTET_STREAM);byte[] zero=new byte[8388608];
            for(int i=0;i<128;i++)assertThat(http.exchange("/api/v1/resources/"+id+"/chunks/"+i,HttpMethod.PUT,new HttpEntity<>(i==0?first:zero,h),Map.class).getStatusCode().value()).isEqualTo(200);
            assertThat((List)f.call(HttpMethod.GET,"/resources/"+id,u,null).getBody().get("uploadedParts")).hasSize(128);
            f.call(HttpMethod.POST,"/resources/"+id+"/complete",u,null);
            Map r=null;for(int i=0;i<240;i++){r=f.call(HttpMethod.GET,"/resources/"+id,u,null).getBody();if(!"PROCESSING".equals(r.get("state")))break;Thread.sleep(500);}
            assertThat(r).containsEntry("state","READY");assertThat(((Number)r.get("size")).longValue()).isEqualTo(1073741824L);
        }finally{
            clock.offset=25*3600000L;f.call(HttpMethod.POST,"/admin/resources/cleanup",f.admin(),null);clock.offset=0;
        }
    }
    @Test void expiryCleanupHonorsSuccessfulProgressAndDoesNotRemoveBoundResources()throws Exception{
        String name="ttl"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        f.call(HttpMethod.POST,"/auth/register",null,Map.of("username",name,"password","password123"));
        String u=(String)f.call(HttpMethod.POST,"/auth/login",null,Map.of("username",name,"password","password123")).getBody().get("token");
        String a=f.admin();var orphan=upload(u,"POST",png(),"orphan.png").getBody();var bound=upload(u,"POST",png(),"bound.png").getBody();
        var p=f.call(HttpMethod.POST,"/posts",u,Map.of("title","有效绑定","body","正文","resourceIds",List.of(bound.get("id")))).getBody();
        f.call(HttpMethod.POST,"/admin/posts/"+p.get("id")+"/review",a,Map.of("revision",1,"decision","APPROVE"));
        var task=f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",8388609)).getBody();String id=(String)task.get("id");
        clock.offset=23*3600000L;
        String renewed=(String)f.call(HttpMethod.POST,"/auth/login",null,Map.of("username",name,"password","password123")).getBody().get("token");
        var h=new HttpHeaders();h.setBearerAuth(renewed);h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(http.exchange("/api/v1/resources/"+id+"/chunks/0",HttpMethod.PUT,new HttpEntity<>(new byte[8388608],h),Map.class).getStatusCode().value()).isEqualTo(200);
        clock.offset=25*3600000L;
        assertThat(f.call(HttpMethod.GET,"/resources/"+orphan.get("id"),renewed,null).getStatusCode().value()).isEqualTo(410);
        assertThat(f.call(HttpMethod.GET,"/resources/"+id,renewed,null).getStatusCode().value()).isEqualTo(200);
        assertThat(read((String)bound.get("url"),null).getStatusCode().value()).isEqualTo(200);
        var cleanup=f.call(HttpMethod.POST,"/admin/resources/cleanup",f.admin(),null);
        assertThat(cleanup.getStatusCode().value()).isEqualTo(200);assertThat(((Number)cleanup.getBody().get("removed")).intValue()).isGreaterThan(0);
        assertThat(read((String)bound.get("url"),null).getStatusCode().value()).isEqualTo(200);
    }
    @Test void chunkedVideoResumesProcessesThroughRabbitAndGuardsPlaylistAndSegments()throws Exception{
        String u=f.user(),a=f.admin(),other=f.user();byte[] bytes=video(2);
        var init=f.call(HttpMethod.POST,"/resources/videos",u,Map.of("size",bytes.length));
        assertThat(init.getStatusCode().value()).isEqualTo(201);String id=(String)init.getBody().get("id");
        String path="/resources/"+id;
        assertThat(f.call(HttpMethod.POST,"/posts",u,Map.of("title","未就绪","body","正文","resourceIds",List.of(id))).getStatusCode().value()).isEqualTo(400);
        var h=new HttpHeaders();h.setBearerAuth(u);h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(http.exchange("/api/v1"+path+"/chunks/0",HttpMethod.PUT,new HttpEntity<>(new byte[]{1},h),Map.class).getStatusCode().value()).isEqualTo(400);
        assertThat(http.exchange("/api/v1"+path+"/chunks/0",HttpMethod.PUT,new HttpEntity<>(bytes,h),Map.class).getStatusCode().value()).isEqualTo(200);
        assertThat((List)f.call(HttpMethod.GET,path,u,null).getBody().get("uploadedParts")).containsExactly(0);
        assertThat(f.call(HttpMethod.GET,path,other,null).getStatusCode().value()).isEqualTo(404);
        assertThat(f.call(HttpMethod.POST,path+"/complete",u,null).getStatusCode().value()).isEqualTo(200);
        Map resource=null;
        for(int i=0;i<120;i++){resource=f.call(HttpMethod.GET,path,u,null).getBody();if(!"PROCESSING".equals(resource.get("state")))break;Thread.sleep(500);}
        assertThat(resource).containsEntry("state","READY");
        var p=f.call(HttpMethod.POST,"/posts",u,Map.of("title","视频","body","正文","resourceIds",List.of(id)));
        assertThat(p.getStatusCode().value()).isEqualTo(201);
        String url=(String)resource.get("url");
        assertThat(read(url,null).getStatusCode().value()).isEqualTo(404);
        var playlist=read(url,u);assertThat(playlist.getStatusCode().value()).isEqualTo(200);
        String segment=Arrays.stream(new String(playlist.getBody(),java.nio.charset.StandardCharsets.UTF_8).split("\\R")).filter(s->s.endsWith(".ts")).findFirst().orElseThrow();
        String segmentUrl=url.substring(0,url.lastIndexOf('/')+1)+segment;
        assertThat(read(segmentUrl,null).getStatusCode().value()).isEqualTo(404);
        f.call(HttpMethod.POST,"/admin/posts/"+p.getBody().get("id")+"/review",a,Map.of("revision",1,"decision","APPROVE"));
        assertThat(read(url,null).getStatusCode().value()).isEqualTo(200);assertThat(read(segmentUrl,null).getStatusCode().value()).isEqualTo(200);
        var rangeHeaders=new HttpHeaders();rangeHeaders.set("Range","bytes=0-15");
        var partial=http.exchange((String)resource.get("originalUrl"),HttpMethod.GET,new HttpEntity<>(rangeHeaders),byte[].class);
        assertThat(partial.getStatusCode().value()).isEqualTo(206);assertThat(partial.getBody()).hasSize(16);
        f.call(HttpMethod.DELETE,"/posts/"+p.getBody().get("id"),u,null);
        for(String token:Arrays.asList(null,u,a)){assertThat(read(url,token).getStatusCode().value()).isEqualTo(404);assertThat(read(segmentUrl,token).getStatusCode().value()).isEqualTo(404);}
    }
    @Test void imageUploadBindsAtomicallyAndAllStatesEnforceDirectAccess()throws Exception{
        String u=f.user(),other=f.user(),a=f.admin();byte[] bytes=png();
        var response=upload(u,"POST",bytes,"image.png");assertThat(response.getStatusCode().value()).isEqualTo(201);
        var resource=response.getBody();String url=(String)resource.get("url");
        assertThat(read(url,u).getBody()).isEqualTo(bytes);assertThat(read(url,null).getStatusCode().value()).isEqualTo(404);
        assertThat(read(url,other).getStatusCode().value()).isEqualTo(404);
        var invalid=f.call(HttpMethod.POST,"/posts",other,Map.of("title","盗用","body","正文","resourceIds",List.of(resource.get("id"))));
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        var p=f.call(HttpMethod.POST,"/posts",u,Map.of("title","图文","body","正文","resourceIds",List.of(resource.get("id"))));
        assertThat(p.getStatusCode().value()).isEqualTo(201);String path="/posts/"+p.getBody().get("id");
        assertThat((List)p.getBody().get("attachments")).hasSize(1);assertThat(read(url,a).getStatusCode().value()).isEqualTo(200);
        f.call(HttpMethod.POST,"/admin"+path+"/review",a,Map.of("revision",1,"decision","APPROVE"));
        assertThat(read(url,null).getStatusCode().value()).isEqualTo(200);
        assertThat(f.call(HttpMethod.PUT,path,u,Map.of("revision",1,"title","失败修改","body","正文","resourceIds",List.of("missing"))).getStatusCode().value()).isEqualTo(400);
        assertThat(f.call(HttpMethod.GET,path,null,null).getStatusCode().value()).isEqualTo(200);
        f.call(HttpMethod.PUT,path,u,Map.of("revision",1,"title","保留图片重审","body","正文","resourceIds",List.of(resource.get("id"))));
        assertThat(read(url,null).getStatusCode().value()).isEqualTo(404);
        f.call(HttpMethod.DELETE,path,u,null);
        for(String token:Arrays.asList(null,u,a))assertThat(read(url,token).getStatusCode().value()).isEqualTo(404);
    }
}
