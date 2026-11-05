package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DshDockerSupervisorTest {
    @TempDir Path root;
    @Test void selectedDockerModeNeverFallsBackToNativeOnMissingImageConfiguration() throws Exception {
        Path marker=root.resolve("native-started");
        Path executable=Files.writeString(root.resolve("dsh"),"#!/bin/sh\ntouch '"+marker+"'\n");executable.toFile().setExecutable(true);
        var launch=new DshLaunchSpec(List.of(executable.toString(),"--profile","sdk"),root.resolve("homes/w/a"),root);
        var supervisor=new DshProcessSupervisor("docker","/missing/docker","",new ObjectMapper());
        assertThrows(Exception.class,()->supervisor.start(launch,Map.of()));
        assertFalse(Files.exists(marker),"strict mode must not invoke native fallback");
    }
    @Test void unknownModeIsRejectedRatherThanSilentlyUsingNative() throws Exception {
        var launch=new DshLaunchSpec(List.of("/bin/sh","-c","exit 0"),root.resolve("homes/w/a"),root);
        assertThrows(Exception.class,()->new DshProcessSupervisor("typo","docker","",new ObjectMapper()).start(launch,Map.of()));
    }

    @Test void strictLaunchPinsDaemonImageAndOwnershipWithoutPuttingCredentialsInArguments() throws Exception {
        var json=new ObjectMapper();
        Path pkg=Files.createDirectories(root.resolve("managed"));
        Files.writeString(pkg.resolve("package.json"),"{\"name\":\"@deepseek-ai/dsh\",\"version\":\"0.2.1-rc.2\"}");
        Path executable=Files.writeString(pkg.resolve("dsh"),"#!/bin/sh\nexit 99\n");executable.toFile().setExecutable(true);
        Path calls=root.resolve("calls.jsonl");
        String id="a".repeat(64),image="sha256:"+"b".repeat(64);
        Path docker=Files.writeString(root.resolve("docker"),"""
                #!/usr/bin/env python3
                import sys,json,os,time
                a=sys.argv[1:]
                with open(%s,'a') as f: f.write(json.dumps({'argv':a,'secret':os.getenv('DEEPSEEK_API_KEY')})+'\\n')
                if a[0]=='context': print('"unix:///test/pinned.sock"')
                elif 'inspect' in a: print(json.dumps({'vip.mateclaw.dsh.version':'0.2.1-rc.2','vip.mateclaw.dsh.lock-sha256':%s}))
                elif 'create' in a or 'rm' in a: print(%s)
                elif 'start' in a: time.sleep(30)
                """.formatted(json.writeValueAsString(calls.toString()),json.writeValueAsString(DshProcessSupervisor.lockHash("0.2.1-rc.2")),json.writeValueAsString(id)));
        docker.toFile().setExecutable(true);
        Path cwd=Files.createDirectories(root.resolve("workspace, with spaces"));
        Path patch=Files.writeString(root.resolve("patch, one.js"),"// patch");
        var launch=new DshLaunchSpec(List.of(executable.toString(),"--profile","sdk","--patch",patch.toString()),root.resolve("homes/w/a"),cwd);
        Process process=new DshProcessSupervisor("docker",docker.toString(),json.writeValueAsString(Map.of("0.2.1-rc.2",image)),json)
                .start(launch,Map.of("DEEPSEEK_API_KEY","fixture-secret"));
        process.destroy();
        var records=Files.readAllLines(calls).stream().map(line->{try{return json.readTree(line);}catch(Exception e){throw new RuntimeException(e);}}).toList();
        var create=records.stream().filter(r->r.path("argv").toString().contains("\"create\"")).findFirst().orElseThrow();
        String argv=create.path("argv").toString();
        assertFalse(argv.contains("fixture-secret"));assertEquals("fixture-secret",create.path("secret").asText());
        assertTrue(argv.contains("--pull=never"));assertTrue(argv.contains(image));assertTrue(argv.contains("readonly"));
        assertFalse(argv.contains("--privileged"));assertFalse(argv.contains("--pid=host"));
        assertTrue(records.stream().filter(r->!r.path("argv").get(0).asText().equals("context"))
                .allMatch(r->r.path("argv").get(0).asText().equals("--host")&&r.path("argv").get(1).asText().equals("unix:///test/pinned.sock")));
        assertTrue(records.stream().anyMatch(r->r.path("argv").toString().contains("\"rm\"")&&r.path("argv").toString().contains(id)));
    }
    @Test void managedCandidateLauncherResolvesItsExactPackageBeforeCheckingImage() throws Exception {
        Path pkg=Files.createDirectories(root.resolve("sdk/node_modules/@deepseek-ai/dsh"));
        Files.writeString(pkg.resolve("package.json"), "{\"name\":\"@deepseek-ai/dsh\",\"version\":\"0.2.2-rc.1\"}");
        Files.writeString(root.resolve("sdk/package.json"), "{\"name\":\"mateclaw-dsh-runtime\",\"dependencies\":{\"@deepseek-ai/dsh\":\"0.2.2-rc.1\"}}");
        Path bin=Files.createDirectories(root.resolve("sdk/bin"));
        Path launcher=Files.writeString(bin.resolve("dsh"), "#!/bin/sh\nexit 99\n"); launcher.toFile().setExecutable(true);
        var launch=new DshLaunchSpec(List.of(launcher.toString(),"--profile","sdk"),root.resolve("homes/w/a"),root);
        var error=assertThrows(Exception.class,()->new DshProcessSupervisor("docker","/missing/docker","{\"0.2.2-rc.1\":\"runtime:latest\"}",new ObjectMapper()).start(launch,Map.of()));
        assertEquals("DSH_DOCKER_IMMUTABLE_IMAGE_REQUIRED", error.getMessage());
    }

    @Test void mutableImageTagIsRejectedBeforeDockerOrSdkStarts() throws Exception {
        Path pkg=Files.createDirectories(root.resolve("sdk"));
        Files.writeString(pkg.resolve("package.json"),"{\"name\":\"@deepseek-ai/dsh\",\"version\":\"0.2.1-rc.2\"}");
        Path executable=Files.writeString(pkg.resolve("dsh"),"#!/bin/sh\nexit 99\n");executable.toFile().setExecutable(true);
        var launch=new DshLaunchSpec(List.of(executable.toString(),"--profile","sdk"),root.resolve("homes/w/a"),root);
        var error=assertThrows(Exception.class,()->new DshProcessSupervisor("docker","/missing/docker","{\"0.2.1-rc.2\":\"runtime:latest\"}",new ObjectMapper()).start(launch,Map.of()));
        assertEquals("DSH_DOCKER_IMMUTABLE_IMAGE_REQUIRED",error.getMessage());
    }
    @Test void mismatchedImageMetadataCannotReachCreate() throws Exception {
        Path pkg=Files.createDirectories(root.resolve("sdk"));
        Files.writeString(pkg.resolve("package.json"),"{\"name\":\"@deepseek-ai/dsh\",\"version\":\"0.2.1-rc.2\"}");
        Path executable=Files.writeString(pkg.resolve("dsh"),"#!/bin/sh\nexit 99\n");executable.toFile().setExecutable(true);
        Path marker=root.resolve("create-started");
        Path docker=Files.writeString(root.resolve("docker"),"""
                #!/bin/sh
                case "$*" in
                  *context*) printf '%%s\\n' '"unix:///test/pinned.sock"';;
                  *inspect*) printf '%%s\\n' '{"vip.mateclaw.dsh.version":"0.2.1-rc.2","vip.mateclaw.dsh.lock-sha256":"wrong"}';;
                  *) touch '%s'; exit 1;;
                esac
                """.formatted(marker));
        docker.toFile().setExecutable(true);
        var launch=new DshLaunchSpec(List.of(executable.toString(),"--profile","sdk"),root.resolve("homes/w/a"),root);
        String images=new ObjectMapper().writeValueAsString(Map.of("0.2.1-rc.2","sha256:"+"a".repeat(64)));
        var error=assertThrows(Exception.class,()->new DshProcessSupervisor("docker",docker.toString(),images,new ObjectMapper()).start(launch,Map.of()));
        assertEquals("DSH_DOCKER_IMAGE_PROVENANCE_MISMATCH",error.getMessage());assertFalse(Files.exists(marker));
    }
}
