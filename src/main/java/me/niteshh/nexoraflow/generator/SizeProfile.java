package me.niteshh.nexoraflow.generator;

/**
 * Dataset size. MEDIUM is the challenge baseline (400 employees, 800 leave requests, 1,000 expense claims).
 * {@code anomalyScale} scales the number of deliberately designed anomaly scenarios relative to MEDIUM.
 */
public enum SizeProfile {
    SMALL(50, 100, 125, 0.15),
    MEDIUM(400, 800, 1000, 1.0),
    LARGE(2000, 4000, 5000, 5.0);

    private final int employees;
    private final int leaveRequests;
    private final int expenseClaims;
    private final double anomalyScale;

    SizeProfile(int employees, int leaveRequests, int expenseClaims, double anomalyScale) {
        this.employees = employees;
        this.leaveRequests = leaveRequests;
        this.expenseClaims = expenseClaims;
        this.anomalyScale = anomalyScale;
    }

    public int employees() {
        return employees;
    }

    public int leaveRequests() {
        return leaveRequests;
    }

    public int expenseClaims() {
        return expenseClaims;
    }

    public double anomalyScale() {
        return anomalyScale;
    }

    /** Scales a MEDIUM anomaly count (at least 1 so that every scenario is represented in every profile). */
    public int scaled(int mediumCount) {
        return Math.max(1, (int) Math.round(mediumCount * anomalyScale));
    }

    public static SizeProfile parse(String value) {
        return SizeProfile.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
