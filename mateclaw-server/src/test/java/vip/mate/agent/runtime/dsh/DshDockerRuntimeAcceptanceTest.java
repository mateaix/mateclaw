package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.runtime.contract.*;
import vip.mate.agent.runtime.dsh.management.*;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Docker daemon, immutable SDK image, model and deliberately orphaned tool grandchildren. */
@EnabledIfEnvironmentVariable(named="MATECLAW_DSH_ACCEPTANCE_DOCKER_IMAGE",matches="sha256:[0-9a-f]{64}")
@Timeout(value=5,unit=TimeUnit.MINUTES)
class DshDockerRuntimeAcceptanceTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    DshRuntimeService service;
    RuntimeSession session;
    final List<String> containers=new CopyOnWriteArrayList<>();
    String image;
    @BeforeEach void setup() throws Exception {
        image=System.getenv("MATECLAW_DSH_ACCEPTANCE_DOCKER_IMAGE");
        String key=System.getenv("DEEPSEEK_API_KEY"),url=System.getenv().getOrDefault("DEEPSEEK_BASE_URL","https://api.deepseek.com/anthropic");
        String database=System.getenv("MATECLAW_DSH_ACCEPTANCE_DATABASE");
        if(database!=null)try(var c=DriverManager.getConnection("jdbc:h2:file:"+database+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;ACCESS_MODE_DATA=r;IFEXISTS=TRUE","sa","");var s=c.createStatement();var r=s.executeQuery("select api_key,base_url from mate_model_provider where provider_id='deepseek'")) {
            assertTrue(r.next());key=r.getString(1);url=r.getString(2);
        }
        assertTrue(key!=null&&!key.isBlank());
        String exe=System.getenv("MATECLAW_DSH_ACCEPTANCE_EXECUTABLE");assertNotNull(exe);
        var config=mock(DshRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new DshRuntimeConfiguration(exe,"",temp.toString(),url,"deepseek-v4-flash",key,"sdk",List.of(),temp.resolve("homes").toString()));
        var models=mock(ModelConfigService.class);var model=new ModelConfigEntity();model.setModelName("deepseek-v4-flash");model.setMaxTokens(4096);model.setMaxInputTokens(128000);when(models.resolveModel(any())).thenReturn(model);
        service=new DshRuntimeService(json,models,mock(ModelProviderService.class),config,new ConversationWindowProperties());
        service.setProcessSupervisor(new DshProcessSupervisor("docker","docker",json.writeValueAsString(Map.of(System.getenv().getOrDefault("MATECLAW_DSH_ACCEPTANCE_VERSION","0.2.1-rc.2"),image)),json));
        session=new RuntimeSession("docker-test","docker-test-"+UUID.randomUUID(),771L,772L,"deepseek-v4-flash",temp,Map.of());

    }
    @AfterEach void cleanup() {for(String id:containers)try{docker("rm","--force",id);}catch(Exception ignored){}}
    String docker(String... args) throws Exception {
        var command=new ArrayList<>(List.of("docker"));command.addAll(List.of(args));
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
        try{assertTrue(p.waitFor(20,TimeUnit.SECONDS));String out=new String(p.getInputStream().readAllBytes()).trim();if(p.exitValue()!=0)throw new java.io.IOException(out);return out;}finally{if(p.isAlive())p.destroyForcibly();}
    }
    static void await(java.util.function.BooleanSupplier condition,int seconds)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(50);assertTrue(condition.getAsBoolean(),"bounded Docker acceptance condition failed");
    }
    void check(String operation) throws Exception {
        var c=service.start(session);var events=new CopyOnWriteArrayList<RuntimeEvent>();var error=new AtomicReference<Throwable>();var done=new CountDownLatch(1);
        String fixture="""
                function main() {
                  const {spawn}=require('node:child_process'),fs=require('node:fs');
                  if(process.argv[2]==='leaf') {
                    fs.writeFileSync('/tmp/escaped.pid',String(process.pid));
                    fs.appendFileSync('/tmp/heartbeat','x');
                    setInterval(()=>fs.appendFileSync('/tmp/heartbeat','x'),100);
                  } else {
                    const stage=process.argv[2]==='middle'?'leaf':'middle';
                    const child=spawn(process.execPath,[__filename,stage],{detached:true,stdio:'ignore'});
                    child.unref();
                    if(!process.argv[2])setTimeout(()=>{},90000);
                  }
                }
                main();
                """;
        String command="cd /tmp; node detach.cjs; sleep 90";
        var sub=c.prompt("Use bash to execute exactly this test fixture command: "+command+". Do not run other commands. Do not use background execution. After the command reply UNEXPECTED.").subscribe(events::add,e->{error.set(e);done.countDown();},done::countDown);
        try {
            var ownedId=new AtomicReference<String>();
            await(()->{try {String found=docker("ps","--no-trunc","--quiet","--filter","label=vip.mateclaw.dsh.jvm="+ProcessHandle.current().pid());
                if(found.matches("[0-9a-f]{64}")){ownedId.set(found);return true;}return done.getCount()==0;}catch(Exception e){return false;}},30);
            assertNull(error.get(),()->String.valueOf(error.get()));
            String id=ownedId.get();assertNotNull(id);containers.add(id);
            // Install the trusted fixture directly before the model invokes its short Bash
            // command; no model token budget is spent copying an encoded program.
            docker("exec",id,"node","-e","require('fs').writeFileSync('/tmp/detach.cjs',Buffer.from('"+Base64.getEncoder().encodeToString(fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"','base64'))");
            await(()->{try{return "true".equals(docker("exec",id,"node","-e","console.log(require('fs').existsSync('/tmp/heartbeat'))"));}catch(Exception e){return done.getCount()==0;}},120);
            assertNull(error.get(),()->String.valueOf(error.get()));
            assertEquals(1L,done.getCount(), ()->"fixture stopped before cancellation: "+events);
            String pid=docker("exec",id,"node","-e","console.log(require('fs').readFileSync('/tmp/escaped.pid','utf8'))");
            String parent=docker("exec",id,"node","-e","const fs=require('fs'); console.log(fs.readFileSync('/proc/"+pid+"/status','utf8').split('\\n').find(x=>x.startsWith('PPid:')).trim())");
            assertEquals("PPid:\t1",parent,"double-fork leaf must already be reparented to PID 1");
            String detachedSession=docker("exec",id,"node","-e","const fs=require('fs'),s=fs.readFileSync('/proc/"+pid+"/stat','utf8');console.log(s.slice(s.lastIndexOf(')')+2).split(' ')[3])");
            assertEquals(pid,detachedSession,"detached leaf must lead its independent POSIX session");
            // Independent sibling container must not be selected by cleanup.
            String sibling=docker("run","--detach","--network=none","--cap-drop=ALL","--entrypoint","/bin/sleep",image,"120");containers.add(sibling);
            if("cancel".equals(operation))assertTimeoutPreemptively(Duration.ofSeconds(20),()->c.cancel().block());
            else if("sdk-crash".equals(operation)) docker("exec",id,"node","-e","const fs=require('fs');const ids=fs.readFileSync('/proc/1/task/1/children','utf8').trim().split(/\\s+/).map(Number).filter(x=>x>1).sort((a,b)=>a-b);if(!ids.length)throw Error('SDK root identity');process.kill(ids[0],'SIGKILL')");
            else {
                var cli=ProcessHandle.current().descendants().filter(p->p.info().arguments().map(a->Arrays.asList(a).contains(id)).orElse(false)).toList();assertEquals(1,cli.size());cli.getFirst().destroyForcibly();
            }
            assertTrue(done.await(30,TimeUnit.SECONDS));
            assertFalse(events.stream().anyMatch(e->e.type()==RuntimeEventType.COMPLETED));
            if("cancel".equals(operation)){assertNull(error.get());assertEquals(1,events.stream().filter(e->e.type()==RuntimeEventType.CANCELLED).count());}else assertNotNull(error.get());
            await(()->{try{return !docker("ps","--all","--no-trunc","--quiet","--filter","id="+id).contains(id);}catch(Exception e){return false;}},15);
            // The PID namespace no longer exists: no detached leaf can remain alive.
            assertFalse(docker("ps","--all","--no-trunc","--quiet","--filter","id="+id).contains(id));
            assertEquals("true",docker("inspect","--format","{{.State.Running}}",sibling));
            try(var recovery=service.start(session)){
                var recovered=recovery.prompt("Return exactly the nine uppercase ASCII characters RECOVERED with no punctuation, whitespace or explanation. Do not call tools.").collectList().block(Duration.ofSeconds(120));assertNotNull(recovered);
                assertEquals(1,recovered.stream().filter(e->e.type()==RuntimeEventType.COMPLETED).count());
                assertEquals("RECOVERED",recovered.stream().filter(e->e.type()==RuntimeEventType.ASSISTANT_DELTA).map(RuntimeEvent::text).reduce("",String::concat));
            }
            String report=System.getenv("MATECLAW_DSH_ACCEPTANCE_REPORT_DIR");if(report!=null){Path path=Path.of(report);Files.createDirectories(path);json.writerWithDefaultPrettyPrinter().writeValue(path.resolve("docker-"+operation+".json").toFile(),Map.of("success",true,"container",id,"escapedPid",pid,"independentSession",true,"adoptedByPid1",true,"pidNamespaceRemoved",true,"siblingPreserved",true,"image",image,"events",events));}
        }finally{sub.dispose();c.close();}
    }
    @Test void cancelCleansDetachedDoubleForkAndPreservesSibling()throws Exception{check("cancel");}
    @Test void sdkCrashCleansDetachedDoubleForkAndPreservesSibling()throws Exception{check("sdk-crash");}
    @Test void attachClientCrashCleansDetachedDoubleForkAndPreservesSibling()throws Exception{check("client-crash");}
}
