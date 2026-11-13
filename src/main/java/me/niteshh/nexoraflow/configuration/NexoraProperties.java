package me.niteshh.nexoraflow.configuration;

import java.time.LocalDate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Environment-driven application configuration (see application.yml). */
@ConfigurationProperties(prefix = "nexoraflow")
public record NexoraProperties(
        @DefaultValue("BR-1.0.0") String businessRulesVersion,
        @DefaultValue("USD") String reportingCurrency,
        Generator generator,
        Expense expense,
        Cli cli) {

    public NexoraProperties {
        if (generator == null) {
            generator = new Generator(20261022L, "MEDIUM", LocalDate.of(2026, 10, 22), "data", true, "docs");
        }
        if (expense == null) {
            expense = new Expense(10, 3, "PDF,JPG,JPEG,PNG,HEIC", 10_485_760L);
        }
        if (cli == null) {
            cli = new Cli("");
        }
    }

    public record Generator(
            @DefaultValue("20261022") long seed,
            @DefaultValue("MEDIUM") String size,
            @DefaultValue("2026-10-22") LocalDate referenceDate,
            @DefaultValue("data") String outputDir,
            @DefaultValue("true") boolean allowReset,
            @DefaultValue("docs") String docsDir) {
    }

    /** Tunable expense-engine parameters. */
    public record Expense(
            @DefaultValue("10") int maxLinesPerClaim,
            @DefaultValue("3") int splitWindowDays,
            @DefaultValue("PDF,JPG,JPEG,PNG,HEIC") String supportedReceiptTypes,
            @DefaultValue("10485760") long maxReceiptSizeBytes) {
    }

    public record Cli(@DefaultValue("") String command) {
    }
}
