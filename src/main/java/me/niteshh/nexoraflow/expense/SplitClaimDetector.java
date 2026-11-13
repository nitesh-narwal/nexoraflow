package me.niteshh.nexoraflow.expense;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import me.niteshh.nexoraflow.common.Money;

/**
 * Detects claims split to stay under an approval threshold.
 *
 * <p>Matching signals (all required unless stated): same employee, same cost center, same category,
 * same merchant (normalised), transaction dates chained within a window of N days. Description
 * similarity is reported as supporting evidence but is not required, because evasion often changes the
 * wording. Suspicion needs at least two distinct claims, each individually at or below an approval boundary
 * B, whose related lines COMBINED exceed B.
 *
 * <p>Limitations (documented): different merchant spellings or different merchants for one purchase are not
 * linked; legitimate repeat purchases at one merchant can be flagged (the outcome is FLAGGED for review,
 * never an automatic rejection); amounts are compared in the reporting currency.
 */
public final class SplitClaimDetector {

    /** A line of this claim or of a candidate claim, with amounts already in the reporting currency. */
    public record RelatedLine(String claimId, String lineId, LocalDate date, BigDecimal amountReporting,
                              BigDecimal claimTotalReporting, String description) {
    }

    public record Finding(BigDecimal boundary, BigDecimal combinedReporting, List<String> relatedClaimIds,
                          List<String> relatedLineIds, double maxDescriptionSimilarity, int windowDays) {
    }

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private SplitClaimDetector() {
    }

    /** Normalises a merchant name for matching: lower case, alphanumerics only. */
    public static String merchantKey(String merchant) {
        return merchant == null ? "" : NON_ALNUM.matcher(merchant.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /**
     * @param ownLines   lines of the claim under evaluation that share merchant and category
     * @param candidates lines of OTHER claims with the same employee, cost center, merchant and category
     * @param boundaries configured approval boundaries, ascending
     */
    public static Optional<Finding> detect(String ownClaimId, List<RelatedLine> ownLines, List<RelatedLine> candidates,
                                           List<BigDecimal> boundaries, int windowDays) {
        if (ownLines.isEmpty() || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<RelatedLine> component = new ArrayList<>(ownLines);
        List<RelatedLine> remaining = new ArrayList<>(candidates);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (RelatedLine c : new ArrayList<>(remaining)) {
                boolean linked = component.stream()
                        .anyMatch(x -> Math.abs(ChronoUnit.DAYS.between(x.date(), c.date())) <= windowDays);
                if (linked) {
                    component.add(c);
                    remaining.remove(c);
                    grew = true;
                }
            }
        }
        Set<String> relatedClaims = new LinkedHashSet<>();
        Set<String> relatedLines = new LinkedHashSet<>();
        for (RelatedLine l : component) {
            if (!l.claimId().equals(ownClaimId)) {
                relatedClaims.add(l.claimId());
                relatedLines.add(l.lineId());
            }
        }
        if (relatedClaims.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal combined = Money.ZERO;
        for (RelatedLine l : component) {
            combined = combined.add(l.amountReporting());
        }
        BigDecimal largestClaim = component.stream().map(RelatedLine::claimTotalReporting)
                .max(BigDecimal::compareTo).orElse(Money.ZERO);
        for (BigDecimal boundary : boundaries) {
            if (largestClaim.compareTo(boundary) <= 0 && combined.compareTo(boundary) > 0) {
                return Optional.of(new Finding(boundary, Money.scale(combined), List.copyOf(relatedClaims),
                        List.copyOf(relatedLines), maxSimilarity(ownLines, component), windowDays));
            }
        }
        return Optional.empty();
    }

    static double maxSimilarity(List<RelatedLine> own, List<RelatedLine> component) {
        double max = 0;
        for (RelatedLine a : own) {
            for (RelatedLine b : component) {
                if (!a.lineId().equals(b.lineId())) {
                    max = Math.max(max, jaccard(a.description(), b.description()));
                }
            }
        }
        return Math.round(max * 100.0) / 100.0;
    }

    static double jaccard(String a, String b) {
        Set<String> ta = tokens(a);
        Set<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(ta);
        inter.retainAll(tb);
        Set<String> union = new HashSet<>(ta);
        union.addAll(tb);
        return (double) inter.size() / union.size();
    }

    private static Set<String> tokens(String text) {
        if (text == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String t : NON_ALNUM.split(text.toLowerCase(Locale.ROOT))) {
            if (t.length() > 1) {
                out.add(t);
            }
        }
        return out;
    }
}
