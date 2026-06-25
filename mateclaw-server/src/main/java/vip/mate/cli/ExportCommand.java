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
    @Override public String description() { return "Generate the operational data report (9-sheet Excel, written to stdout)"; }

    @Override public String usage() {
        return """
               \s
                 export — generate the operational data report; ZIP bytes are written to stdout
               \s
                 Required:
                   --cli.start=YYYY-MM-DD   start date (inclusive)
                   --cli.end=YYYY-MM-DD     end date (inclusive)
               \s
                 Optional:
                   --cli.dry-run            dry-run mode
               \s
                 Example:
                   java -jar app.jar --cli.command=export \\
                \s    --cli.start=2026-01-01 --cli.end=2026-06-30 > report.zip
               \s""";
    }

    @Override
    public void execute(MateClawCli.CliContext ctx) {
        LocalDate start = ctx.requireDate("cli.start");
        LocalDate end   = ctx.requireDate("cli.end");

        if (ctx.isDryRun()) {
            ctx.header("Export dry-run");
            ctx.info("Date range", start + " ~ " + end);
            ctx.info("Result", "ZIP bytes would be written to stdout (not executed)");
            ctx.done("Dry-run complete");
            ctx.exit(0);
            return;
        }

        byte[] zip = exportService.exportBackendBytes(start, end);

        try {
            // Diagnostics go to stderr so stdout stays a clean binary stream for redirection.
            System.err.println("=== Operational data export ===");
            System.err.printf("  Date range : %s ~ %s%n", start, end);
            System.err.printf("  Size       : %d KB%n", zip.length / 1024);
            System.err.printf("  File name  : ops_data_%s_%s.zip%n", start, end);
            System.err.println("\n=== Writing to stdout (redirect: ... > report.zip) ===");
            System.out.write(zip);
            System.out.flush();
            System.err.println("=== Export complete ===");
        } catch (IOException e) {
            ctx.error("Failed to write to stdout: " + e.getMessage());
        }

        ctx.exit(0);
    }
}
