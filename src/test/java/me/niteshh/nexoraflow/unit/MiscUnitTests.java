package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.Money;
import me.niteshh.nexoraflow.common.id.IdService;
import me.niteshh.nexoraflow.configuration.CurrencyService;
import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.expense.ExpensePolicyResolver;
import me.niteshh.nexoraflow.expense.Receipt;
import me.niteshh.nexoraflow.expense.ReceiptValidator;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.generator.GeneratorSeeds;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.SizeProfile;
import me.niteshh.nexoraflow.generator.catalog.HolidayCatalog;
import me.niteshh.nexoraflow.generator.master.OrgChartBuilder;
import me.niteshh.nexoraflow.generator.plan.ApprovalOracle;
import me.niteshh.nexoraflow.generator.plan.OrgIndex;
import me.niteshh.nexoraflow.leave.LeaveAccrualService;
import me.niteshh.nexoraflow.leave.LeaveProvisioningService;
import me.niteshh.nexoraflow.policy.ExpenseCategory;
import me.niteshh.nexoraflow.policy.ExpensePolicy;
import me.niteshh.nexoraflow.policy.PolicyEmployeeType;
import me.niteshh.nexoraflow.policy.PolicyStatus;

class MiscUnitTests {

    // ---------------------------------------------------------------- money and currency

    @Test
    void moneyUsesBigDecimalWithHalfUpRounding() {
        assertThat(Money.scale(new BigDecimal("2.345"))).isEqualTo(new BigDecimal("2.35"));
        assertThat(Money.scale(new BigDecimal("2.344"))).isEqualTo(new BigDecimal("2.34"));
        // the classic floating point trap does not exist with BigDecimal
        assertThat(new BigDecimal("0.10").add(new BigDecimal("0.20"))).isEqualByComparingTo("0.30");
        assertThat(Money.sum(List.of(new BigDecimal("0.10"), new BigDecimal("0.20"), new BigDecimal("0.30")))).isEqualTo(new BigDecimal("0.60"));
    }

    @Test
    void currencyConversionUsesTheDocumentedRateAndRoundsToCents() {
        assertThat(CurrencyService.convert(new BigDecimal("4166.67"), new BigDecimal("0.012"))).isEqualTo(new BigDecimal("50.00"));
        assertThat(CurrencyService.convert(new BigDecimal("100.00"), new BigDecimal("1.27"))).isEqualTo(new BigDecimal("127.00"));
    }

    // ---------------------------------------------------------------- receipts

    @Test
    void receiptMetadataIsValidatedForTypeSizeAndStatus() {
        ReceiptValidator validator = new ReceiptValidator("PDF,JPG", 1000);
        Receipt ok = receipt("pdf", 500, "VALID");
        assertThat(validator.validate(ok).valid()).isTrue();
        assertThat(validator.validate(receipt("exe", 500, "VALID")).valid()).isFalse();
        assertThat(validator.validate(receipt("PDF", 5000, "VALID")).valid()).isFalse();
        assertThat(validator.validate(receipt("PDF", 0, "VALID")).valid()).isFalse();
        assertThat(validator.validate(receipt("PDF", 500, "INVALID")).valid()).isFalse();
        assertThat(validator.isSupportedType(" jpg ")).isTrue();
    }

    private static Receipt receipt(String type, long size, String status) {
        Receipt r = new Receipt();
        r.setFileType(type);
        r.setFileSizeBytes(size);
        r.setReceiptStatus(status);
        return r;
    }

    // ---------------------------------------------------------------- policy resolution (effective dates)

    private static ExpensePolicy policy(String id, PolicyEmployeeType type, String from, String to) {
        ExpensePolicy p = new ExpensePolicy();
        p.setExpensePolicyId(id);
        p.setExpenseCategory(ExpenseCategory.MEALS);
        p.setEmployeeType(type);
        p.setEffectiveFrom(LocalDate.parse(from));
        p.setEffectiveTo(to == null ? null : LocalDate.parse(to));
        p.setPolicyStatus(PolicyStatus.ACTIVE);
        return p;
    }

    @Test
    void policiesAreSelectedByEffectiveDateAndEmployeeType() {
        ExpensePolicy v1 = policy("V1", PolicyEmployeeType.ALL, "2025-01-01", "2026-03-31");
        ExpensePolicy v2 = policy("V2", PolicyEmployeeType.ALL, "2026-04-01", null);
        ExpensePolicy interns = policy("INTERN", PolicyEmployeeType.INTERN, "2025-01-01", null);
        List<ExpensePolicy> all = List.of(v1, v2, interns);
        assertThat(ExpensePolicyResolver.select(all, EmploymentType.FULL_TIME, LocalDate.of(2026, 3, 31)).orElseThrow().getExpensePolicyId()).isEqualTo("V1");
        assertThat(ExpensePolicyResolver.select(all, EmploymentType.FULL_TIME, LocalDate.of(2026, 4, 1)).orElseThrow().getExpensePolicyId()).isEqualTo("V2");
        assertThat(ExpensePolicyResolver.select(all, EmploymentType.INTERN, LocalDate.of(2026, 5, 1)).orElseThrow().getExpensePolicyId()).isEqualTo("INTERN");
        assertThat(ExpensePolicyResolver.select(all, EmploymentType.FULL_TIME, LocalDate.of(2024, 12, 31))).isEmpty();
        ExpensePolicy inactive = policy("OFF", PolicyEmployeeType.ALL, "2025-01-01", null);
        inactive.setPolicyStatus(PolicyStatus.INACTIVE);
        assertThat(ExpensePolicyResolver.select(List.of(inactive), EmploymentType.FULL_TIME, LocalDate.of(2026, 5, 1))).isEmpty();
    }

    // ---------------------------------------------------------------- leave accrual and provisioning arithmetic

    @Test
    void monthlyAccrualInstalmentsSumToTheAnnualEntitlement() {
        BigDecimal entitlement = new BigDecimal("25");
        BigDecimal total = BigDecimal.ZERO;
        for (int k = 1; k <= 12; k++) {
            BigDecimal instalment = cumulative(entitlement, k).subtract(cumulative(entitlement, k - 1));
            assertThat(instalment.signum()).isPositive();
            total = total.add(instalment);
        }
        assertThat(total).isEqualByComparingTo("25.00");
    }

    private static BigDecimal cumulative(BigDecimal entitlement, int k) {
        try {
            var m = LeaveAccrualService.class.getDeclaredMethod("cumulative", BigDecimal.class, int.class);
            m.setAccessible(true);
            return (BigDecimal) m.invoke(null, entitlement, k);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void newJoinersReceiveAProRatedEntitlement() {
        BigDecimal full = new BigDecimal("18");
        assertThat(LeaveProvisioningService.prorate(full, LocalDate.of(2020, 3, 1), 2026)).isEqualByComparingTo("18.00");
        assertThat(LeaveProvisioningService.prorate(full, LocalDate.of(2026, 1, 15), 2026)).isEqualByComparingTo("18.00");
        assertThat(LeaveProvisioningService.prorate(full, LocalDate.of(2026, 7, 1), 2026)).isEqualByComparingTo("9.00"); // July..December
        assertThat(LeaveProvisioningService.prorate(full, LocalDate.of(2027, 1, 5), 2026)).isEqualByComparingTo("0.00");
    }

    // ---------------------------------------------------------------- ids and clock

    @Test
    void businessIdsFollowTheDocumentedConventions() {
        assertThat(IdService.pad(1, 6)).isEqualTo("000001");
        assertThat(IdService.expenseLineId("EXP-2026-000001", 1)).isEqualTo("EXPL-EXP-2026-000001-01");
        assertThat(IdService.approvalStepId("LVE-2026-000001", 1)).isEqualTo("APR-LVE-2026-000001-01");
        assertThat(IdService.approvalStepId("EXP-2026-000123", 12)).isEqualTo("APR-EXP-2026-000123-12");
    }

    @Test
    void theApplicationClockCanBePinnedForDeterministicTimestamps() {
        AppClock clock = new AppClock();
        Instant pinned = Instant.parse("2026-03-01T08:30:00Z");
        clock.runAt(pinned, () -> {
            assertThat(clock.now()).isEqualTo(pinned);
            assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 3, 1));
        });
        assertThat(clock.now()).isAfter(pinned); // back on the wall clock afterwards
    }

    // ---------------------------------------------------------------- deterministic randomness

    @Test
    void seededStreamsAreReproducibleAndIndependentPerStage() {
        GeneratorSeeds a = new GeneratorSeeds(20261022L);
        GeneratorSeeds b = new GeneratorSeeds(20261022L);
        assertThat(a.random("leave").nextLong()).isEqualTo(b.random("leave").nextLong());
        assertThat(a.random("leave").nextLong()).isNotEqualTo(a.random("expense").nextLong());
        assertThat(new GeneratorSeeds(1L).random("leave").nextLong()).isNotEqualTo(a.random("leave").nextLong());
        assertThat(a.faker("x", "en-IN").name().firstName()).isEqualTo(b.faker("x", "en-IN").name().firstName());
    }

    // ---------------------------------------------------------------- organisation chart

    @Test
    void generatedOrganisationHasAValidHierarchyAndIsDeterministic() {
        GeneratorSettings settings = new GeneratorSettings(20261022L, SizeProfile.SMALL, LocalDate.of(2026, 10, 22));
        List<Employee> first = new OrgChartBuilder(settings, new GeneratorSeeds(settings.seed())).build();
        List<Employee> second = new OrgChartBuilder(settings, new GeneratorSeeds(settings.seed())).build();
        assertThat(first).hasSize(50);
        assertThat(first).extracting(Employee::getEmail).containsExactlyElementsOf(second.stream().map(Employee::getEmail).toList());
        assertThat(first.stream().map(Employee::getEmail).collect(Collectors.toSet())).hasSize(50); // unique e-mails
        assertThat(first.stream().map(Employee::getEmployeeId).collect(Collectors.toSet())).hasSize(50);
        Set<String> seen = new HashSet<>();
        for (Employee e : first) {
            if (e.getManagerId() != null) {
                assertThat(seen).as("manager of %s must be inserted first", e.getEmployeeId()).contains(e.getManagerId());
                Employee manager = first.stream().filter(m -> m.getEmployeeId().equals(e.getManagerId())).findFirst().orElseThrow();
                assertThat(manager.getJobLevel()).isLessThan(e.getJobLevel());
                assertThat(e.getEmployeeId()).isNotEqualTo(e.getManagerId());
            }
            assertThat(e.getTerminationDate() == null || !e.getTerminationDate().isBefore(e.getJoiningDate())).isTrue();
            seen.add(e.getEmployeeId());
        }
        assertThat(first.stream().filter(e -> e.getManagerId() == null)).hasSize(1);
        assertThat(first.stream().filter(e -> "Finance Manager".equals(e.getDesignation()))).hasSizeGreaterThanOrEqualTo(2);
        assertThat(first).extracting(Employee::getEmail).allMatch(email -> email.endsWith("@nexora-technologies.example"));
    }

    @Test
    void theApprovalOracleRestatesTheDocumentedThresholds() {
        assertThat(ApprovalOracle.expenseRoles(new BigDecimal("500"), false)).containsExactly("DIRECT_MANAGER");
        assertThat(ApprovalOracle.expenseRoles(new BigDecimal("500.01"), false)).containsExactly("MANAGER");
        assertThat(ApprovalOracle.expenseRoles(new BigDecimal("2000.01"), false)).containsExactly("MANAGER", "FINANCE_REVIEWER");
        assertThat(ApprovalOracle.expenseRoles(new BigDecimal("5000.01"), false)).containsExactly("MANAGER", "FINANCE_HEAD");
        assertThat(ApprovalOracle.expenseRoles(new BigDecimal("300"), true)).containsExactly("DIRECT_MANAGER", "FINANCE_REVIEWER");
        assertThat(ApprovalOracle.leaveRoles(10)).containsExactly("DIRECT_MANAGER");
        assertThat(ApprovalOracle.leaveRoles(11)).containsExactly("DIRECT_MANAGER", "DEPARTMENT_HEAD");
    }

    @Test
    void organisationIndexWalksTheReportingLine() {
        Employee ceo = emp("EMP-000001", null, 1);
        Employee head = emp("EMP-000002", "EMP-000001", 2);
        Employee manager = emp("EMP-000003", "EMP-000002", 3);
        Employee ic = emp("EMP-000004", "EMP-000003", 5);
        OrgIndex org = new OrgIndex(List.of(ic, manager, head, ceo));
        assertThat(org.ancestors(ic)).extracting(Employee::getEmployeeId).containsExactly("EMP-000003", "EMP-000002", "EMP-000001");
        assertThat(org.nearestActiveAncestor(ic, 3).orElseThrow().getEmployeeId()).isEqualTo("EMP-000003");
        assertThat(org.nearestActiveAncestor(ic, 2).orElseThrow().getEmployeeId()).isEqualTo("EMP-000002");
        assertThat(org.ceo().getEmployeeId()).isEqualTo("EMP-000001");
    }

    private static Employee emp(String id, String manager, int level) {
        Employee e = new Employee();
        e.setEmployeeId(id);
        e.setManagerId(manager);
        e.setJobLevel(level);
        e.setEmploymentStatus(me.niteshh.nexoraflow.employee.EmploymentStatus.ACTIVE);
        return e;
    }

    // ---------------------------------------------------------------- holiday calendars

    @Test
    void holidayCalendarsUseTheEasterComputusAndObservedRules() {
        assertThat(HolidayCatalog.easter(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(HolidayCatalog.easter(2027)).isEqualTo(LocalDate.of(2027, 3, 28));
        List<LocalDate> uk = HolidayCatalog.holidays("GB", 2026).stream().map(HolidayCatalog.NamedHoliday::date).toList();
        assertThat(uk).contains(LocalDate.of(2026, 4, 3), LocalDate.of(2026, 4, 6)); // Good Friday, Easter Monday
        List<LocalDate> us = HolidayCatalog.holidays("US", 2026).stream().map(HolidayCatalog.NamedHoliday::date).toList();
        assertThat(us).contains(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 25)); // July 4 is a Saturday -> observed Friday
        for (String country : List.of("IN", "US", "GB", "DE", "SG")) {
            assertThat(HolidayCatalog.holidays(country, 2026)).as(country).hasSizeGreaterThanOrEqualTo(7);
            assertThat(HolidayCatalog.holidays(country, 2026).stream().map(HolidayCatalog.NamedHoliday::date)).contains(LocalDate.of(2026, 12, 25));
        }
        assertThatThrownBy(() -> HolidayCatalog.holidays("XX", 2026)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- ground-truth isolation

    @Test
    void decisionEnginesNeverReferenceGroundTruthOrTheGenerator() throws Exception {
        List<String> enginePackages = List.of("leave", "expense", "approval", "reimbursement", "budget", "audit", "policy", "employee",
                "department", "costcenter", "intake");
        Path root = Path.of("src/main/java/me/niteshh/nexoraflow");
        for (String pkg : enginePackages) {
            try (Stream<Path> files = Files.walk(root.resolve(pkg))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    assertThat(source).as(file + " must not import the generator").doesNotContain("nexoraflow.generator");
                    assertThat(source).as(file + " must not import the evaluation module").doesNotContain("nexoraflow.evaluation");
                    assertThat(source).as(file + " must not read the ground-truth schema").doesNotContain("ground_truth");
                }
            }
        }
    }

    @Test
    void controllersStayThinAndDoNotExposeEntities() throws Exception {
        Path root = Path.of("src/main/java/me/niteshh/nexoraflow");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith("Controller.java")).toList()) {
                String source = Files.readString(file);
                assertThat(source).as(file + " must not use repositories' save/delete directly").doesNotContain(".save(").doesNotContain(".delete(");
            }
        }
    }
}
