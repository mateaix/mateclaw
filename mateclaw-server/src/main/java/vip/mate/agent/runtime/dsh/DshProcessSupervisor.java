package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.agent.runtime.dsh.management.DshPinnedInstaller;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Optional native Docker PID boundary. No shell commands or weak-path fallback. */
@Component
public class DshProcessSupervisor {
    private final String mode;
    private final String docker;
    private final String images;
    private final ObjectMapper mapper;

    public DshProcessSupervisor(
            @Value("${mateclaw.agent.runtime.dsh.containment.mode:native}") String mode,
            @Value("${mateclaw.agent.runtime.dsh.containment.docker-executable:docker}") String docker,
            @Value("${mateclaw.agent.runtime.dsh.containment.images-json:}") String images,
            ObjectMapper mapper) {
        this.mode=mode;this.docker=docker;this.images=images;this.mapper=mapper;
    }
    public Map<String,Object> status() {
        return Map.of("mode",mode,"requiresDocker", "docker".equals(mode),"verified",false);
    }
    public Process start(DshLaunchSpec launch, Map<String,String> environment) throws Exception {
        if ("native".equals(mode)) return launch.processBuilder(environment).start();
        if (!"docker".equals(mode)) throw new IOException("DSH_CONTAINMENT_MODE_INVALID");
        if (images.isBlank()) throw new IOException("DSH_DOCKER_IMAGE_REQUIRED");
        var catalog=mapper.readTree(images);
        if (!catalog.isObject()) throw new IOException("DSH_DOCKER_IMAGE_CATALOG_INVALID");
        String version=managedVersion(launch.command().getFirst());
        String image=catalog.path(version).asText("");
        if (!image.matches("sha256:[0-9a-f]{64}")) throw new IOException("DSH_DOCKER_IMMUTABLE_IMAGE_REQUIRED");
        var prefix=dockerPrefix();
        String labelsText=command(prefix,List.of("image","inspect","--format","{{json .Config.Labels}}",image),Map.of());
        var labels=mapper.readTree(labelsText);
        if (!version.equals(labels.path("vip.mateclaw.dsh.version").asText())
                || !lockHash(version).equals(labels.path("vip.mateclaw.dsh.lock-sha256").asText()))
            throw new IOException("DSH_DOCKER_IMAGE_PROVENANCE_MISMATCH");
        // Reuse all existing home/symlink validation before creating a container.
        Map<String,String> sdkEnvironment=new HashMap<>(launch.processBuilder(environment).environment());
        if (launch.cwd().getParent()==null) throw new IOException("DSH_DOCKER_WORKSPACE_ROOT_INVALID");
        String owner=UUID.randomUUID().toString();
        var args=new ArrayList<>(List.of("create","--pull=never","--name","mateclaw-dsh-"+owner,
                "--label","vip.mateclaw.dsh.owner="+owner,"--label","vip.mateclaw.dsh.jvm="+ProcessHandle.current().pid(),
                "--init","--interactive","--cap-drop=ALL","--security-opt=no-new-privileges","--pids-limit=256",
                "--read-only","--tmpfs","/tmp:rw,exec,nosuid,nodev,size=128m","--user",hostIdentity(),"--workdir",launch.cwd().toString()));
        mount(args,launch.home(),false);mount(args,launch.cwd(),false);
        for(int i=3;i<launch.command().size();i+=2) {
            if (!"--patch".equals(launch.command().get(i)) || i+1>=launch.command().size()) throw new IOException("DSH_DOCKER_ARGUMENT_INVALID");
            mount(args,Path.of(launch.command().get(i+1)),true);
        }
        sdkEnvironment.put("HOME",launch.home().toString());
        for(String key:List.of("DEEPSEEK_API_KEY","DEEPSEEK_BASE_URL","DSH_HOME","DSH_AGENTS_HOME","DSH_CWD","MATECLAW_DSH_MODEL","MATECLAW_DSH_CONTEXT_WINDOW","MATECLAW_DSH_MAX_TOKENS")) {
            if(sdkEnvironment.containsKey(key)){args.add("--env");args.add(key);}
        }
        args.add("--env");args.add("HOME="+launch.home());
        args.add("--env");args.add("SHELL=/bin/bash");
        args.add(image);args.addAll(launch.command().subList(1,launch.command().size()));
        String id;
        try {
            id=command(prefix,args,sdkEnvironment).trim();
            if(!id.matches("[0-9a-f]{64}")) throw new IOException("DSH_DOCKER_CREATE_ID_INVALID");
        } catch (Exception failure) {
            // create may have succeeded before the client lost its response. No SDK has
            // started yet. Recover only the exact random name with our ownership label.
            try {
                String recovered=command(prefix,List.of("container","ls","--all","--no-trunc","--quiet",
                        "--filter","name=^/mateclaw-dsh-"+owner+"$","--filter","label=vip.mateclaw.dsh.owner="+owner),Map.of());
                if(recovered.matches("[0-9a-f]{64}")) remove(prefix,recovered);
                else if(!recovered.isEmpty()) throw new IOException("DSH_DOCKER_CREATE_OWNERSHIP_UNCONFIRMED");
            } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        try {
            var cli=builder(prefix,List.of("start","--attach","--interactive",id),Map.of()).start();
            return new DshDockerProcess(cli,()->remove(prefix,id),id);
        } catch(Exception failure) {
            try { remove(prefix,id); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private static String remove(List<String> prefix,String id) throws Exception {
        try {
            String result=command(prefix,List.of("rm","--force",id),Map.of());
            if(id.equals(result))return id;
        } catch (Exception failedRemoval) {
            // A previous removal may have succeeded before a lost CLI response. Confirm
            // absence against the same pinned daemon; daemon failure cannot prove absence.
            String remaining=command(prefix,List.of("container","ls","--all","--no-trunc","--quiet","--filter","id="+id),Map.of());
            if(remaining.isEmpty())return id;
            throw failedRemoval;
        }
        throw new IOException("DSH_DOCKER_REMOVAL_ID_INVALID");
    }
    static String lockHash(String version) throws Exception {
        try(var input=DshProcessSupervisor.class.getResourceAsStream("/dsh/locks/"+version+".json")) {
            if(input==null)throw new IOException("DSH_DOCKER_LOCK_MISSING");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }
    private String managedVersion(String executable) throws Exception {
        Path path=Path.of(executable).toRealPath().getParent();
        for(int i=0;i<4 && path!=null;i++,path=path.getParent()) {
            for (Path file : List.of(path.resolve("package.json"), path.resolve("node_modules/@deepseek-ai/dsh/package.json"))) {
                if(!Files.isRegularFile(file))continue;
                var value=mapper.readTree(file.toFile());
                if("@deepseek-ai/dsh".equals(value.path("name").asText())) {
                    String version=value.path("version").asText();DshPinnedInstaller.release(version);return version;
                }
            }
        }
        throw new IOException("DSH_DOCKER_MANAGED_SDK_REQUIRED");
    }
    private List<String> dockerPrefix() throws Exception {
        String endpoint=System.getenv("DOCKER_HOST");
        if(endpoint==null || endpoint.isBlank())endpoint=mapper.readTree(command(List.of(docker),List.of("context","inspect","--format","{{json .Endpoints.docker.Host}}"),Map.of())).asText();
        if(!endpoint.startsWith("unix://"))throw new IOException("DSH_DOCKER_LOCAL_UNIX_ENDPOINT_REQUIRED");
        return List.of(docker,"--host",endpoint);
    }
    private static String hostIdentity() throws IOException {
        try {var unix=new com.sun.security.auth.module.UnixSystem();return unix.getUid()+":"+unix.getGid();}
        catch(Throwable unsupported){throw new IOException("DSH_DOCKER_HOST_PLATFORM_UNSUPPORTED");}
    }
    private static void mount(List<String> args,Path path,boolean readOnly) throws IOException {
        Path source=path.toRealPath();
        // --mount uses CSV parsing. Quote fields independently; no shell interpretation.
        String src="\"source="+source.toString().replace("\"","\"\"")+"\"";
        String dst="\"target="+path.toAbsolutePath().normalize().toString().replace("\"","\"\"")+"\"";
        args.add("--mount");args.add("type=bind,"+src+","+dst+(readOnly?",readonly":""));
    }
    private static ProcessBuilder builder(List<String> prefix,List<String> args,Map<String,String> extra) {
        var argv=new ArrayList<>(prefix);argv.addAll(args);var b=new ProcessBuilder(argv);
        b.environment().clear();
        for(String key:List.of("PATH","HOME","TMPDIR","DOCKER_CONFIG","DOCKER_CERT_PATH","DOCKER_TLS_VERIFY","SSL_CERT_FILE")) {
            String value=System.getenv(key);if(value!=null)b.environment().put(key,value);
        }
        // Only selected SDK variables are given to docker create, never in argv.
        for(String key:List.of("DEEPSEEK_API_KEY","DEEPSEEK_BASE_URL","DSH_HOME","DSH_AGENTS_HOME","DSH_CWD","MATECLAW_DSH_MODEL","MATECLAW_DSH_CONTEXT_WINDOW","MATECLAW_DSH_MAX_TOKENS"))if(extra.containsKey(key))b.environment().put(key,extra.get(key));
        // HOME is required by Docker for its config as well as by the container. Use an explicit
        // container HOME value, avoiding changing the client's config lookup.
        return b;
    }
    private static String command(List<String> prefix,List<String> args,Map<String,String> env) throws Exception {
        Process p=builder(prefix,args,env).redirectErrorStream(true).start();
        try {
            if(!p.waitFor(15,TimeUnit.SECONDS))throw new IOException("DSH_DOCKER_CONTROL_TIMEOUT");
            byte[] output=p.getInputStream().readNBytes(65537);
            if(p.exitValue()!=0 || output.length>65536)throw new IOException("DSH_DOCKER_CONTROL_FAILED");
            return new String(output,StandardCharsets.UTF_8).trim();
        } finally {if(p.isAlive())p.destroyForcibly();}
    }
}
