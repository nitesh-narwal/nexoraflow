package me.niteshh.nexoraflow.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Single source of "now" for the whole application.
 *
 * <p>Production code reads the wall clock. The synthetic-data simulation overrides the clock for the
 * current thread so that every timestamp written to the database is a function of the seed, never of
 * the wall clock. This is what makes generated datasets reproducible.
 *
 * <p>All business dates are UTC dates (documented assumption).
 */
@Component
public class AppClock {

    private static final ThreadLocal<Instant> SIMULATED = new ThreadLocal<>();

    public Instant now() {
        return currentInstant();
    }

    public LocalDate today() {
        return currentInstant().atZone(ZoneOffset.UTC).toLocalDate();
    }

    /** Static accessor for places where injection is impractical (JPA lifecycle callbacks). */
    public static Instant currentInstant() {
        Instant simulated = SIMULATED.get();
        return simulated != null ? simulated : Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static void setSimulatedTime(Instant instant) {
        SIMULATED.set(instant.truncatedTo(ChronoUnit.MICROS));
    }

    public static void clearSimulatedTime() {
        SIMULATED.remove();
    }

    /** Runs the action with the clock pinned to {@code instant} for the current thread. */
    public <T> T runAt(Instant instant, Supplier<T> action) {
        Instant previous = SIMULATED.get();
        setSimulatedTime(instant);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clearSimulatedTime();
            } else {
                SIMULATED.set(previous);
            }
        }
    }

    public void runAt(Instant instant, Runnable action) {
        runAt(instant, () -> {
            action.run();
            return null;
        });
    }
}
