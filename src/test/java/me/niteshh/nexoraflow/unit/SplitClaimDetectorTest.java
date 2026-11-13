package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.expense.SplitClaimDetector;
import me.niteshh.nexoraflow.expense.SplitClaimDetector.Finding;
import me.niteshh.nexoraflow.expense.SplitClaimDetector.RelatedLine;

class SplitClaimDetectorTest {

    private static final List<BigDecimal> BOUNDARIES = List.of(new BigDecimal("500"), new BigDecimal("2000"), new BigDecimal("5000"));

    private static RelatedLine line(String claim, String lineId, int day, String amount, String claimTotal, String description) {
        return new RelatedLine(claim, lineId, LocalDate.of(2026, 10, day), new BigDecimal(amount), new BigDecimal(claimTotal), description);
    }

    @Test
    void relatedClaimsThatTogetherCrossAThresholdAreSuspicious() {
        List<RelatedLine> own = List.of(line("EXP-2026-000002", "L2", 20, "250", "250", "client dinner at the bistro"));
        List<RelatedLine> others = List.of(line("EXP-2026-000001", "L1", 19, "300", "300", "client dinner at the bistro"));
        Optional<Finding> f = SplitClaimDetector.detect("EXP-2026-000002", own, others, BOUNDARIES, 3);
        assertThat(f).isPresent();
        assertThat(f.get().boundary()).isEqualByComparingTo("500");
        assertThat(f.get().combinedReporting()).isEqualByComparingTo("550");
        assertThat(f.get().relatedClaimIds()).containsExactly("EXP-2026-000001");
        assertThat(f.get().maxDescriptionSimilarity()).isEqualTo(1.0);
    }

    @Test
    void combinedAmountBelowTheThresholdIsNotSuspicious() {
        Optional<Finding> f = SplitClaimDetector.detect("B", List.of(line("B", "L2", 20, "150", "150", "x")),
                List.of(line("A", "L1", 19, "120", "120", "x")), BOUNDARIES, 3);
        assertThat(f).isEmpty();
    }

    @Test
    void aClaimThatIsAlreadyAboveTheBoundaryItselfIsNotAnEvasion() {
        // 600 alone is already in the next approval tier, so splitting it cannot evade the 500 threshold
        Optional<Finding> f = SplitClaimDetector.detect("B", List.of(line("B", "L2", 20, "600", "600", "x")),
                List.of(line("A", "L1", 19, "100", "100", "x")), BOUNDARIES, 3);
        assertThat(f).isEmpty();
    }

    @Test
    void transactionsOutsideTheWindowAreUnrelated() {
        Optional<Finding> f = SplitClaimDetector.detect("B", List.of(line("B", "L2", 20, "300", "300", "x")),
                List.of(line("A", "L1", 10, "300", "300", "x")), BOUNDARIES, 3);
        assertThat(f).isEmpty();
    }

    @Test
    void chainsOfClaimsAreLinkedTransitively() {
        // day 12 -> 14 -> 16: each hop is within 3 days even though 12 and 16 are four days apart
        List<RelatedLine> others = List.of(line("A", "L1", 12, "200", "200", "x"), line("C", "L3", 14, "200", "200", "x"));
        Optional<Finding> f = SplitClaimDetector.detect("B", List.of(line("B", "L2", 16, "200", "200", "x")), others, BOUNDARIES, 3);
        assertThat(f).isPresent();
        assertThat(f.get().relatedClaimIds()).containsExactlyInAnyOrder("A", "C");
        assertThat(f.get().combinedReporting()).isEqualByComparingTo("600");
    }

    @Test
    void thresholdsAreReadFromConfigurationNotHardCoded() {
        List<BigDecimal> custom = List.of(new BigDecimal("1000"));
        assertThat(SplitClaimDetector.detect("B", List.of(line("B", "L2", 20, "300", "300", "x")),
                List.of(line("A", "L1", 19, "300", "300", "x")), custom, 3)).isEmpty();
        assertThat(SplitClaimDetector.detect("B", List.of(line("B", "L2", 20, "600", "600", "x")),
                List.of(line("A", "L1", 19, "600", "600", "x")), custom, 3)).isPresent();
    }

    @Test
    void merchantNamesAreNormalisedForMatching() {
        assertThat(SplitClaimDetector.merchantKey("Bistro  Nova!")).isEqualTo(SplitClaimDetector.merchantKey("bistro nova"));
        assertThat(SplitClaimDetector.merchantKey("Bistro Nova")).isNotEqualTo(SplitClaimDetector.merchantKey("Bistro Nova 2"));
    }

    @Test
    void noCandidatesMeansNoFinding() {
        assertThat(SplitClaimDetector.detect("B", List.of(line("B", "L2", 20, "300", "300", "x")), List.of(), BOUNDARIES, 3)).isEmpty();
    }
}
