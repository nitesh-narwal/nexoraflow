package me.niteshh.nexoraflow.generator.master;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import me.niteshh.nexoraflow.employee.Employee;
import me.niteshh.nexoraflow.employee.EmploymentStatus;
import me.niteshh.nexoraflow.employee.EmploymentType;
import me.niteshh.nexoraflow.generator.GeneratorSeeds;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.catalog.PolicyCatalog;
import net.datafaker.Faker;

/**
 * Builds a valid organisation: CEO -> department heads -> managers -> team leads -> employees. Every
 * manager reference points to an employee with a lower job level (so the hierarchy is acyclic by
 * construction) and employees are returned parents-first, ready for foreign-key-safe insertion.
 */
public final class OrgChartBuilder {

    static final String[] COUNTRY_ORDER = {"IN", "US", "GB", "DE", "SG"};
    private static final int[] COUNTRY_WEIGHTS = {45, 20, 12, 13, 10};
    private static final Map<String, String> LOCALE = Map.of("IN", "en-IN", "US", "en-US", "GB", "en-GB", "DE", "de", "SG", "en-GB");
    private static final Map<String, String[]> CITIES = Map.of(
            "IN", new String[] {"Bengaluru", "Pune", "Hyderabad", "Chennai"},
            "US", new String[] {"San Francisco", "Austin", "New York", "Seattle"},
            "GB", new String[] {"London", "Manchester", "Edinburgh"},
            "DE", new String[] {"Berlin", "Munich", "Hamburg"},
            "SG", new String[] {"Singapore"});
    private static final Map<String, Double> DEPARTMENT_SHARE = new java.util.LinkedHashMap<>();
    private static final Map<String, String[]> IC_TITLES = new HashMap<>();

    static {
        DEPARTMENT_SHARE.put("DEP-ENG", 0.34);
        DEPARTMENT_SHARE.put("DEP-PRD", 0.08);
        DEPARTMENT_SHARE.put("DEP-HR", 0.05);
        DEPARTMENT_SHARE.put("DEP-FIN", 0.07);
        DEPARTMENT_SHARE.put("DEP-SAL", 0.18);
        DEPARTMENT_SHARE.put("DEP-MKT", 0.07);
        DEPARTMENT_SHARE.put("DEP-OPS", 0.10);
        DEPARTMENT_SHARE.put("DEP-CS", 0.11);
        IC_TITLES.put("DEP-ENG", new String[] {"Software Engineer", "Senior Software Engineer", "QA Engineer", "DevOps Engineer", "Data Engineer"});
        IC_TITLES.put("DEP-PRD", new String[] {"Product Analyst", "Associate Product Manager", "UX Designer", "Product Designer"});
        IC_TITLES.put("DEP-HR", new String[] {"HR Executive", "Talent Acquisition Specialist", "HR Business Partner", "Payroll Specialist"});
        IC_TITLES.put("DEP-FIN", new String[] {"Accountant", "Financial Analyst", "Accounts Payable Specialist", "Treasury Analyst"});
        IC_TITLES.put("DEP-SAL", new String[] {"Account Executive", "Sales Development Representative", "Solutions Consultant", "Sales Analyst"});
        IC_TITLES.put("DEP-MKT", new String[] {"Content Marketer", "Digital Marketing Specialist", "Brand Designer", "Marketing Analyst"});
        IC_TITLES.put("DEP-OPS", new String[] {"Operations Analyst", "Logistics Coordinator", "Facilities Coordinator", "Procurement Specialist"});
        IC_TITLES.put("DEP-CS", new String[] {"Customer Success Associate", "Support Engineer", "Onboarding Specialist", "Customer Support Analyst"});
    }

    private final GeneratorSettings settings;
    private final Random rng;
    private final Map<String, Faker> fakers = new HashMap<>();
    private final GeneratorSeeds seeds;
    private final Set<String> usedEmails = new HashSet<>();
    private final List<Employee> result = new ArrayList<>();
    private int nextId = 1;

    public OrgChartBuilder(GeneratorSettings settings, GeneratorSeeds seeds) {
        this.settings = settings;
        this.seeds = seeds;
        this.rng = seeds.random("master|org");
    }

    public List<Employee> build() {
        int total = settings.profile().employees();
        int rest = total - 1 - PolicyCatalog.DEPARTMENTS.size();
        Map<String, Integer> sizes = departmentSizes(rest);

        Employee ceo = person("DEP-ENG", "Chief Executive Officer", 1, null, "US", EmploymentType.FULL_TIME,
                LocalDate.of(2015, 1, 5), EmploymentStatus.ACTIVE, null);
        List<Employee> heads = new ArrayList<>();
        String[] headCountries = {"US", "IN", "GB", "DE", "US", "IN", "GB", "SG"};
        int h = 0;
        for (var dept : PolicyCatalog.DEPARTMENTS.entrySet()) {
            heads.add(person(dept.getKey(), dept.getValue()[1], 2, ceo.getEmployeeId(), headCountries[h++ % headCountries.length],
                    EmploymentType.FULL_TIME, LocalDate.of(2015 + rng.nextInt(4), 1 + rng.nextInt(12), 1 + rng.nextInt(27)),
                    EmploymentStatus.ACTIVE, null));
        }

        Map<String, List<Employee>> managersByDept = new HashMap<>();
        Map<String, List<Employee>> leadsByDept = new HashMap<>();
        for (Employee head : heads) {
            String dept = head.getDepartmentId();
            int size = sizes.get(dept);
            int managers = dept.equals("DEP-FIN") ? Math.max(3, Math.round(size / 9f)) : Math.max(1, Math.round(size / 13f));
            managers = Math.min(managers, dept.equals("DEP-FIN") ? Math.max(1, size - 2) : Math.max(1, size / 2));
            List<Employee> list = new ArrayList<>();
            for (int i = 0; i < managers; i++) {
                // finance managers are spread over countries so a same-country reviewer usually exists
                String country = dept.equals("DEP-FIN") ? new String[] {"US", "IN", "GB", "DE"}[i % 4] : pickCountry();
                list.add(person(dept, PolicyCatalog.DEPARTMENTS.get(dept)[2], 3, head.getEmployeeId(), country, EmploymentType.FULL_TIME,
                        between(LocalDate.of(2016, 1, 1), LocalDate.of(2022, 12, 31), head.getJoiningDate()), EmploymentStatus.ACTIVE, null));
            }
            managersByDept.put(dept, list);
        }
        for (Employee head : heads) {
            String dept = head.getDepartmentId();
            int size = sizes.get(dept);
            int managers = managersByDept.get(dept).size();
            int leads = Math.max(0, Math.round((size - managers) / 8f));
            List<Employee> list = new ArrayList<>();
            for (int i = 0; i < leads; i++) {
                Employee manager = managersByDept.get(dept).get(i % managers);
                String country = rng.nextInt(100) < 80 ? manager.getCountry() : pickCountry();
                list.add(person(dept, PolicyCatalog.DEPARTMENTS.get(dept)[3], 4, manager.getEmployeeId(), country, EmploymentType.FULL_TIME,
                        between(LocalDate.of(2017, 1, 1), LocalDate.of(2024, 12, 31), manager.getJoiningDate()), EmploymentStatus.ACTIVE, null));
            }
            leadsByDept.put(dept, list);
        }
        for (Employee head : heads) {
            String dept = head.getDepartmentId();
            int size = sizes.get(dept);
            int ics = size - managersByDept.get(dept).size() - leadsByDept.get(dept).size();
            List<Employee> leads = leadsByDept.get(dept);
            List<Employee> managers = managersByDept.get(dept);
            for (int i = 0; i < ics; i++) {
                Employee manager = !leads.isEmpty() && rng.nextInt(100) < 85 ? leads.get(rng.nextInt(leads.size()))
                        : managers.get(rng.nextInt(managers.size()));
                String country = rng.nextInt(100) < 80 ? manager.getCountry() : pickCountry();
                String[] titles = IC_TITLES.get(dept);
                int typeRoll = rng.nextInt(100);
                EmploymentType type = typeRoll < 85 ? EmploymentType.FULL_TIME : typeRoll < 90 ? EmploymentType.PART_TIME
                        : typeRoll < 95 ? EmploymentType.CONTRACTOR : EmploymentType.INTERN;
                LocalDate joined = icJoiningDate(manager);
                EmploymentStatus status = EmploymentStatus.ACTIVE;
                LocalDate terminated = null;
                int statusRoll = rng.nextInt(100);
                if (statusRoll < 3 && joined.isBefore(LocalDate.of(2025, 12, 31))) {
                    status = EmploymentStatus.TERMINATED;
                    LocalDate earliest = joined.plusDays(90).isAfter(LocalDate.of(2026, 2, 2)) ? joined.plusDays(90) : LocalDate.of(2026, 2, 2);
                    terminated = earliest.plusDays(rng.nextInt(Math.max(1, (int) (LocalDate.of(2026, 9, 30).toEpochDay() - earliest.toEpochDay()))));
                } else if (statusRoll < 5) {
                    status = EmploymentStatus.INACTIVE;
                }
                person(dept, titles[rng.nextInt(titles.length)], 5, manager.getEmployeeId(), country, type, joined, status, terminated);
            }
        }
        markInactiveManagers(managersByDept);
        return result;
    }

    /** A few managers are on extended absence (INACTIVE). Their reports stay active and are reserved for scenarios. */
    private void markInactiveManagers(Map<String, List<Employee>> managersByDept) {
        int count = switch (settings.profile()) {
            case SMALL -> 1;
            case MEDIUM -> 2;
            case LARGE -> 6;
        };
        List<Employee> candidates = new ArrayList<>();
        for (String dept : List.of("DEP-ENG", "DEP-SAL", "DEP-OPS", "DEP-CS", "DEP-MKT", "DEP-PRD")) {
            candidates.addAll(managersByDept.getOrDefault(dept, List.of()));
        }
        // choose managers that actually have reports
        Set<String> managersWithReports = new HashSet<>();
        result.forEach(e -> {
            if (e.getManagerId() != null) {
                managersWithReports.add(e.getManagerId());
            }
        });
        int marked = 0;
        for (int i = candidates.size() - 1; i >= 0 && marked < count; i--) {
            Employee m = candidates.get(i);
            if (managersWithReports.contains(m.getEmployeeId())) {
                m.setEmploymentStatus(EmploymentStatus.INACTIVE);
                marked++;
            }
        }
    }

    private Map<String, Integer> departmentSizes(int rest) {
        Map<String, Integer> sizes = new java.util.LinkedHashMap<>();
        int assigned = 0;
        for (var e : DEPARTMENT_SHARE.entrySet()) {
            int s = (int) Math.round(rest * e.getValue());
            sizes.put(e.getKey(), s);
            assigned += s;
        }
        sizes.merge("DEP-ENG", rest - assigned, Integer::sum);
        // Finance needs enough people for several managers and reviewers
        int minFinance = Math.min(8, Math.max(5, rest / 15));
        if (sizes.get("DEP-FIN") < minFinance) {
            int deficit = minFinance - sizes.get("DEP-FIN");
            sizes.put("DEP-FIN", minFinance);
            sizes.merge("DEP-ENG", -deficit, Integer::sum);
        }
        return sizes;
    }

    private LocalDate icJoiningDate(Employee manager) {
        int roll = rng.nextInt(100);
        LocalDate from;
        LocalDate to;
        if (roll < 70) {
            from = LocalDate.of(2018, 1, 1);
            to = LocalDate.of(2025, 6, 30);
        } else if (roll < 90) {
            from = LocalDate.of(2025, 7, 1);
            to = LocalDate.of(2025, 12, 31);
        } else {
            from = LocalDate.of(2026, 3, 2);
            to = LocalDate.of(2026, 8, 31);
        }
        return between(from, to, manager.getJoiningDate());
    }

    private LocalDate between(LocalDate from, LocalDate to, LocalDate notBefore) {
        LocalDate start = from.isBefore(notBefore) ? notBefore : from;
        if (start.isAfter(to)) {
            return start;
        }
        return start.plusDays(rng.nextInt((int) (to.toEpochDay() - start.toEpochDay()) + 1));
    }

    private String pickCountry() {
        int roll = rng.nextInt(100);
        int cumulative = 0;
        for (int i = 0; i < COUNTRY_ORDER.length; i++) {
            cumulative += COUNTRY_WEIGHTS[i];
            if (roll < cumulative) {
                return COUNTRY_ORDER[i];
            }
        }
        return "US";
    }

    private Employee person(String dept, String designation, int level, String managerId, String country, EmploymentType type,
                            LocalDate joined, EmploymentStatus status, LocalDate terminated) {
        Faker faker = fakers.computeIfAbsent(country, c -> seeds.faker("master|names", LOCALE.get(c)));
        String first = faker.name().firstName();
        String last = faker.name().lastName();
        Employee e = new Employee();
        e.setEmployeeId("EMP-" + String.format("%06d", nextId++));
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail(uniqueEmail(first, last));
        String phone = faker.phoneNumber().cellPhone();
        e.setPhone(phone.length() > 40 ? phone.substring(0, 40) : phone);
        e.setDepartmentId(dept);
        e.setDesignation(designation);
        e.setJobLevel(level);
        e.setManagerId(managerId);
        e.setCountry(country);
        String[] cities = CITIES.get(country);
        e.setLocation(cities[rng.nextInt(cities.length)]);
        e.setEmploymentType(type);
        e.setEmploymentStatus(status);
        e.setJoiningDate(joined);
        e.setTerminationDate(terminated);
        result.add(e);
        return e;
    }

    private String uniqueEmail(String first, String last) {
        String base = ascii(first) + "." + ascii(last);
        String candidate = base;
        int n = 1;
        while (!usedEmails.add(candidate)) {
            candidate = base + (++n);
        }
        return candidate + "@nexora-technologies.example";
    }

    private static String ascii(String s) {
        String normalized = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String cleaned = normalized.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        return cleaned.isEmpty() ? "user" : cleaned;
    }
}
