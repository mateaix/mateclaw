package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.runtime.contract.*;
import vip.mate.agent.runtime.dsh.management.*;
import vip.mate.config.ConversationWindowProperties;
import vip.mate.llm.service.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DshAnswerDeliveryTest {
    @TempDir Path temp;

    @Test void answerArrivesBeforeIdleAndSlowShutdownWithoutReplayingSettledText() throws Exception {
        verifyDelivery(false);
    }
    @Test void eofAfterDeliveredAnswerDoesNotAppendThatAnswerAgain() throws Exception {
        verifyDelivery(true);
    }
    private void verifyDelivery(boolean crash) throws Exception {
        Path script = temp.resolve("runtime.py");
        Files.writeString(script, """
                #!/usr/bin/env python3
                import json,sys,time,pathlib
                def emit(x): print(json.dumps({'jsonrpc':'2.0',**x}),flush=True)
                init=json.loads(input());emit({'id':init['id'],'result':{'serverInfo':{'name':'deepseek-harness-sdk-runtime'}}})
                prompt=json.loads(input());sid=prompt['params']['sessionId']
                emit({'id':prompt['id'],'result':{'messageId':'m'}})
                def event(seq,typ,data):emit({'method':'session.event','params':{'sessionId':sid,'event':{'seq':seq,'type':typ,'data':data}}})
                event(1,'agent/inbox/spliced',{'inserted':[{'id':'m'}]})
                event(2,'assistant/message',{'message':{'content':[{'type':'text','text':'answer'}]},'usage':{'inputTokens':3,'outputTokens':2}})
                event(3,'turn/end',{'reason':{'kind':'completed'}})
                until=time.monotonic()+8
                while not pathlib.Path('delivered').exists() and time.monotonic()<until:time.sleep(.01)
                if not pathlib.Path('delivered').exists():sys.exit(1)
                if %s:sys.exit(2)
                emit({'method':'session.status','params':{'sessionId':sid,'status':'idle'}})
                input()
                """.formatted(crash ? "True" : "False"));
        assertTrue(script.toFile().setExecutable(true));
        var settings=mock(DshRuntimeConfigService.class);
        when(settings.resolve()).thenReturn(new DshRuntimeConfiguration(script.toString(),"",temp.toString(),"","model","", "sdk",List.of(),temp.resolve("homes").toString()));
        var service=new DshRuntimeService(new ObjectMapper(),mock(ModelConfigService.class),mock(ModelProviderService.class),settings,new ConversationWindowProperties());
        try(var connection=service.start(new RuntimeSession("root","root",1L,2L,"model",temp,Map.of()))) {
            var events=new CopyOnWriteArrayList<RuntimeEvent>();var done=new CountDownLatch(1);var answer=new CountDownLatch(1);
            var subscription=connection.prompt("hello").subscribe(e->{
                events.add(e);
                if(e.type()==RuntimeEventType.ASSISTANT_DELTA)try{Files.writeString(temp.resolve("delivered"),e.text());answer.countDown();}catch(Exception ex){throw new RuntimeException(ex);}
            },e->done.countDown(),done::countDown);
            try {
                assertTrue(answer.await(4,TimeUnit.SECONDS),"must deliver answer before waiting for idle/shutdown");
                assertTrue(done.await(10,TimeUnit.SECONDS));
                assertEquals("answer",events.stream().filter(e->e.type()==RuntimeEventType.ASSISTANT_DELTA).map(RuntimeEvent::text).reduce("",String::concat));
                assertEquals(crash ? 0 : 1, events.stream().filter(e->e.type()==RuntimeEventType.COMPLETED).count());
            } finally { subscription.dispose(); }
        }
    }
}
