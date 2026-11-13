package me.niteshh.nexoraflow.generator.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The documented business policy of the fictional company, expressed once. It is used (a) to SEED the
 * configurable policy / approval-rule tables and (b) by the independent ground-truth oracle. The decision
 * engines never read this class: they read the database rows.
 */
public final class BusinessPolicyBaseline {

    private BusinessPolicyBaseline() {
    }

    /** Approval tier boundaries for expenses in USD-equivalent: (0,500], (500,2000], (2000,5000], (5000,inf). */
    public static final List<BigDecimal> EXPENSE_BOUNDARIES_USD = List.of(new BigDecimal("500"), new BigDecimal("2000"), new BigDecimal("5000"));

    /** Leave longer than this many business days also needs the department head. */
    public static final int LONG_LEAVE_DAYS = 10;

    /** CLIENT_ENTERTAINMENT claims up to this amount need an extra Finance review (compliance override). */
    public static final BigDecimal CLIENT_ENTERTAINMENT_FINANCE_REVIEW_UP_TO_USD = new BigDecimal("2000");

    public static final String FINANCE_MANAGER = "Finance Manager";
    public static final String FINANCE_HEAD = "VP Finance";

    /** Base per-category limits in USD: {per transaction, per day, per month, receipt threshold}. */
    public static final Map<String, int[]> CATEGORY_LIMITS_USD = Map.of(
            "MEALS", new int[] {75, 120, 1000, 25},
            "TRAVEL", new int[] {2500, 3000, 8000, 0},
            "LODGING", new int[] {300, 450, 4000, 0},
            "OFFICE_SUPPLIES", new int[] {400, 800, 1000, 20},
            "TRAINING", new int[] {3000, 3000, 6000, 0},
            "CLIENT_ENTERTAINMENT", new int[] {600, 800, 2000, 0});

    /** Categories restricted to full-time employees. */
    public static final List<String> FULL_TIME_ONLY_CATEGORIES = List.of("TRAINING", "CLIENT_ENTERTAINMENT");

    /** Cost-of-living factor applied to the USD limits of a country. */
    public static final Map<String, Double> COUNTRY_LIMIT_FACTOR = Map.of("US", 1.0, "GB", 1.0, "DE", 1.0, "SG", 1.0, "IN", 0.75);

    /** Fixed conversion rates to USD (documented assumption, not market data). */
    public static final Map<String, String> RATE_TO_USD = Map.of("USD", "1", "INR", "0.012", "GBP", "1.27", "EUR", "1.09", "SGD", "0.74");

    public static final Map<String, String> COUNTRY_CURRENCY = Map.of("US", "USD", "IN", "INR", "GB", "GBP", "DE", "EUR", "SG", "SGD");
    public static final Map<String, String> COUNTRY_NAME = Map.of("US", "United States", "IN", "India", "GB", "United Kingdom",
            "DE", "Germany", "SG", "Singapore");
}
