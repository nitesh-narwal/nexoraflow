package me.niteshh.nexoraflow.generator.plan;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import me.niteshh.nexoraflow.generator.plan.Plans.TimedDecision;
import me.niteshh.nexoraflow.generator.plan.Plans.Verdict;

/** Scripted human behaviour: when approvers act, and what that implies for the final state at the cutoff. */
public final class Timeline {

    public enum Script { APPROVE_ALL, REJECT_AT_FIRST, REJECT_AT_SECOND, APPROVE_FIRST_ONLY, NO_ACTION }

    /** Result of a script. {@code finalAt} is the time of the last executed decision (null when none). */
    public record Result(List<TimedDecision> decisions, String finalStatus, Instant finalAt) {
    }

    private Timeline() {
    }

    public static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    /** Evaluation happens two minutes after submission. */
    public static Instant evaluationTime(Instant submitAt) {
        return submitAt.plusSeconds(120);
    }

    /** A random business-ish time of day (08:00-17:59). */
    public static Instant randomTime(LocalDate date, Random rng) {
        return at(date, 8 + rng.nextInt(10), rng.nextInt(60));
    }

    /**
     * Builds the approver decisions for a request with {@code levels} required approval steps. All random draws
     * are made unconditionally so truncation at the cutoff never changes later draws.
     */
    public static Result script(Script script, int levels, Instant evaluatedAt, Random rng, Instant cutoff) {
        long firstDelayHours = 1 + rng.nextInt(72);
        long nextDelayHours = 2 + rng.nextInt(94);
        List<TimedDecision> decisions = new ArrayList<>();
        Instant t = evaluatedAt.plusSeconds(firstDelayHours * 3600);
        int steps = switch (script) {
            case APPROVE_ALL -> levels;
            case REJECT_AT_FIRST -> 1;
            case REJECT_AT_SECOND -> Math.min(2, levels);
            case APPROVE_FIRST_ONLY -> Math.min(1, levels);
            case NO_ACTION -> 0;
        };
        for (int i = 0; i < steps; i++) {
            boolean last = i == steps - 1;
            Verdict v = (script == Script.REJECT_AT_FIRST || script == Script.REJECT_AT_SECOND) && last ? Verdict.REJECT : Verdict.APPROVE;
            if (!t.isAfter(cutoff)) {
                decisions.add(new TimedDecision(t, v));
            }
            t = t.plusSeconds(nextDelayHours * 3600);
        }
        return new Result(decisions, finalStatus(decisions, levels), decisions.isEmpty() ? null : decisions.get(decisions.size() - 1).at());
    }

    /** Status after applying the executed decisions to a route of {@code levels} steps. */
    public static String finalStatus(List<TimedDecision> decisions, int levels) {
        int approved = 0;
        for (TimedDecision d : decisions) {
            if (d.verdict() == Verdict.REJECT) {
                return "REJECTED";
            }
            approved++;
        }
        return approved >= levels && levels > 0 ? "APPROVED" : "PENDING_APPROVAL";
    }

    /** Picks a script with the baseline mix for the given roll (0..99). */
    public static Script baselineScript(int roll, int levels) {
        if (roll < 82) {
            return Script.APPROVE_ALL;
        }
        if (roll < 89) {
            return levels > 1 && roll >= 87 ? Script.REJECT_AT_SECOND : Script.REJECT_AT_FIRST;
        }
        if (roll < 93) {
            return levels > 1 ? Script.APPROVE_FIRST_ONLY : Script.NO_ACTION;
        }
        return Script.NO_ACTION;
    }
}
