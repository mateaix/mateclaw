package vip.mate.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MateClaw CLI framework — single-file core containing:
 * <ul>
 *   <li>{@link CliCommand} — interface for pluggable commands</li>
 *   <li>{@link CliContext} — argument parsing, output formatting, lifecycle</li>
 *   <li>{@link CliRunner} — auto-discovery dispatcher</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   java -jar app.jar --cli.command=help
 *   java -jar app.jar --cli.command=export \
 *     --cli.start=2026-01-01 --cli.end=2026-06-30 > report.zip
 * }</pre>
 *
 * <h3>Adding a new command</h3>
 * <pre>{@code
 *   @Component
 *   public class MyCommand implements MateClawCli.CliCommand {
 *       public String name()        { return "mycmd"; }
 *       public String description() { return "does something"; }
 *       public String usage()       { return "  --cli.x=...\n  示例: ..."; }
 *       public void execute(MateClawCli.CliContext ctx) {
 *           ctx.exit(0);
 *       }
 *   }
 * }</pre>
 */
public final class MateClawCli { private MateClawCli() { /* namespace */ }

    // ═══ CliCommand — interface for pluggable commands ═══

    public interface CliCommand {
        String name();
        String description();
        default String usage() { return ""; }
        void execute(CliContext ctx);
    }

    // ═══ CliContext — argument parsing & output ═══

    public static class CliContext {
        private static final Logger log = LoggerFactory.getLogger(CliContext.class);
        private final ApplicationArguments args;
        private final ApplicationContext springCtx;
        private final boolean dryRun;

        public CliContext(ApplicationArguments args, ApplicationContext springCtx) {
            this.args = args;
            this.springCtx = springCtx;
            this.dryRun = args.getOptionNames().contains("cli.dry-run");
        }

        /** Read optional string param (null if absent). */
        public String arg(String key) {
            var vals = args.getOptionValues(key);
            return vals != null && !vals.isEmpty() ? vals.get(0) : null;
        }

        /** Read required string param. Absent = error + exit. */
        public String requireArg(String key) {
            String val = arg(key);
            if (val == null || val.isBlank()) error("缺少必要参数: --" + key);
            return val;
        }

        /** Read required date param (YYYY-MM-DD). Bad format = error + exit. */
        public LocalDate requireDate(String key) {
            String raw = requireArg(key);
            try { return LocalDate.parse(raw); }
            catch (DateTimeParseException e) { error("日期格式无效: --" + key + "=" + raw + " (需要 YYYY-MM-DD)"); return null; }
        }

        /** True when {@code --cli.dry-run} was passed. */
        public boolean isDryRun() { return dryRun; }

        // —— output ——————————————————————————————————————————
        public void header(String title) { System.out.println(); System.out.println("=== " + title + " ==="); }
        public void info(String key, Object val) { System.out.printf("  %-12s : %s%n", key, val); }
        public void done(String msg) { System.out.println(); System.out.println("=== " + msg + " ==="); System.out.println(); }
        public void warn(String msg)  { log.warn(msg); System.err.println("[WARN] " + msg); }
        public void error(String msg) { log.error(msg); System.err.println("[ERROR] " + msg); exit(1); }

        public void exit(int code) {
            System.out.flush(); System.err.flush();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            SpringApplication.exit(springCtx, (ExitCodeGenerator) () -> code);
            System.exit(code);
        }
    }

    // ═══ CliRunner — auto-discovery ApplicationRunner ═══

    @Component
    @Order(9999)
    public static class CliRunner implements ApplicationRunner {
        private static final Logger log = LoggerFactory.getLogger(CliRunner.class);
        private final ApplicationContext springCtx;
        private final Map<String, CliCommand> registry;

        public CliRunner(List<CliCommand> commands, ApplicationContext springCtx) {
            var tmp = new TreeMap<String, CliCommand>();
            for (var c : commands) {
                if (tmp.containsKey(c.name())) throw new IllegalStateException("CLI 命令名冲突: '" + c.name() + "'");
                tmp.put(c.name(), c);
            }
            this.registry = Collections.unmodifiableMap(tmp);
            this.springCtx = springCtx;
            log.info("CLI 就绪, 已注册 {} 个命令: {}", registry.size(), registry.keySet());
        }

        @Override public void run(ApplicationArguments args) {
            String cmdName = arg(args, "cli.command");
            if (cmdName == null) return;

            CliContext ctx = new CliContext(args, springCtx);
            if ("help".equalsIgnoreCase(cmdName)) { printHelp(); ctx.exit(0); return; }

            CliCommand cmd = registry.get(cmdName.toLowerCase());
            if (cmd == null) {
                System.err.println("[ERROR] 未知命令: " + cmdName);
                System.err.println("  可用命令: " + String.join(", ", registry.keySet()));
                ctx.exit(1); return;
            }
            try {
                log.info("CLI 执行: {}", cmd.name());
                cmd.execute(ctx);
            } catch (Exception e) {
                log.error("命令 '{}' 失败", cmd.name(), e);
                System.err.println("\n[ERROR] 命令 '" + cmd.name() + "' 执行异常");
                System.err.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
                var trace = e.getStackTrace();
                for (int i = 0; i < Math.min(8, trace.length); i++) System.err.println("    at " + trace[i]);
                ctx.exit(1);
            }
        }

        private void printHelp() {
            System.out.println("\n  MateClaw CLI\n  ═══════════════════════════════════════");
            System.out.println("  java -jar app.jar --cli.command=<name> [options]");
            System.out.println("  Docker: docker exec <容器> java -jar /app/app.jar --cli.command=<name> [options]");
            System.out.println("\n  全局参数:");
            System.out.println("    --cli.command=<name>     要执行的命令");
            System.out.println("    --cli.dry-run            模拟运行");
            System.out.println("\n  可用命令:");
            System.out.printf("    %-12s %s%n", "help", "显示此帮助");
            for (var c : registry.values()) System.out.printf("    %-12s %s%n", c.name(), c.description());
            System.out.println("\n  详细用法:");
            for (var c : registry.values()) { String u = c.usage(); if (!u.isBlank()) System.out.println(u); }
            System.out.println("  Spring Boot 参数可直接追加 (--spring.profiles.active 等)\n");
        }

        static String arg(ApplicationArguments args, String key) {
            var vals = args.getOptionValues(key);
            return vals != null && !vals.isEmpty() ? vals.get(0) : null;
        }
    }
}
