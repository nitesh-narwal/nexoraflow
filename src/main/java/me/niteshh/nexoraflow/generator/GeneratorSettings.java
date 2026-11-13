package me.niteshh.nexoraflow.generator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The complete configuration of one generation run. Same settings + same seed + same application version
 * + same business-rules version => same logical dataset.
 *
 * @param referenceDate the synthetic company's "today"; the simulation never runs past it
 */
public record GeneratorSettings(long seed, SizeProfile profile, LocalDate referenceDate) {

    /** Master data snapshot date: policies, balances and org structure exist from the start of the year. */
    public LocalDate masterDataDate() {
        return LocalDate.of(referenceDate.getYear(), 1, 1);
    }

    public Instant masterDataInstant() {
        return masterDataDate().atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** First day on which requests are submitted (first Monday of the year after the holidays). */
    public LocalDate simulationStart() {
        return LocalDate.of(referenceDate.getYear(), 1, 5);
    }

    /** Last instant of simulated activity: end of business on the reference date. */
    public Instant cutoff() {
        return referenceDate.atTime(18, 0).toInstant(ZoneOffset.UTC);
    }

    public int year() {
        return referenceDate.getYear();
    }
}
