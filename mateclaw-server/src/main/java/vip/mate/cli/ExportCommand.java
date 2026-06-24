package vip.mate.cli;

import org.springframework.stereotype.Component;
import vip.mate.operational.service.OperationalDataExportService;

import java.io.IOException;
import java.time.LocalDate;

/**
 * {@code --cli.command=export} — generate a 9-sheet operational data report.
 *
 * <p>Writes the ZIP bytes to stdout so the caller can redirect:</p>
 * <pre>{@code
 *   java -jar app.jar --cli.command=export \
 *     --cli.start=2026-01-01 --cli.end=2026-06-30 > report.zip
 * }</pre>
 */
@Component
public class ExportCommand implements MateClawCli.CliCommand {

    private final OperationalDataExportService exportService;

    public ExportCommand(OperationalDataExportService exportService) {
        this.exportService = exportService;
    }

    @Override public String name() { return "export"; }
    @Override public String description() { return "生成运营数据报告 (9-sheet Excel, 输出到 stdout)"; }

    @Override public String usage() {
        return """
               \s
                 export —— 生成运营数据报告, ZIP 字节写入 stdout
               \s
                 必填参数:
                   --cli.start=YYYY-MM-DD   开始日期 (含)
                   --cli.end=YYYY-MM-DD     结束日期 (含)
               \s
                 可选参数:
                   --cli.dry-run            模拟运行
               \s
                 示例:
                   java -jar app.jar --cli.command=export \\
                \s    --cli.start=2026-01-01 --cli.end=2026-06-30 > report.zip
               \s""";
    }

    @Override
    public void execute(MateClawCli.CliContext ctx) {
        LocalDate start = ctx.requireDate("cli.start");
        LocalDate end   = ctx.requireDate("cli.end");

        if (ctx.isDryRun()) {
            ctx.header("导出模拟 (dry-run)");
            ctx.info("日期范围", start + " ~ " + end);
            ctx.info("结果", "ZIP 字节将写入 stdout (未实际执行)");
            ctx.done("模拟完成");
            ctx.exit(0);
            return;
        }

        byte[] zip = exportService.exportBackendBytes(start, end);

        try {
            System.err.println("=== 运营数据导出 ===");
            System.err.printf("  日期范围 : %s ~ %s%n", start, end);
            System.err.printf("  大小     : %d KB%n", zip.length / 1024);
            System.err.printf("  文件名   : ops_data_%s_%s.zip%n", start, end);
            System.err.println("\n=== 写入 stdout (重定向: ... > report.zip) ===");
            System.out.write(zip);
            System.out.flush();
            System.err.println("=== 导出完成 ===");
        } catch (IOException e) {
            ctx.error("写入 stdout 失败: " + e.getMessage());
        }

        ctx.exit(0);
    }
}
