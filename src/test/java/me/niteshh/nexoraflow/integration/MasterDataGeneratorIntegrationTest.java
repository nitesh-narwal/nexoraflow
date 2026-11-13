package me.niteshh.nexoraflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import me.niteshh.nexoraflow.common.AppClock;
import me.niteshh.nexoraflow.common.DatabaseResetService;
import me.niteshh.nexoraflow.generator.GeneratorSeeds;
import me.niteshh.nexoraflow.generator.GeneratorSettings;
import me.niteshh.nexoraflow.generator.SizeProfile;
import me.niteshh.nexoraflow.generator.master.MasterDataGenerator;
import me.niteshh.nexoraflow.support.AbstractIntegrationTest;

class MasterDataGeneratorIntegrationTest extends AbstractIntegrationTest {

    @Autowired MasterDataGenerator generator;
    @Autowired JdbcTemplate jdbc;
    @Autowired DatabaseResetService reset;

    @BeforeEach
    void emptyDatabase() {
        AppClock.clearSimulatedTime();
        reset.resetAll();
    }

    @Test
    void generatesAValidSmallCompany() {
        GeneratorSettings settings = new GeneratorSettings(20261022L, SizeProfile.SMALL, LocalDate.of(2026, 10, 22));
        var summary = generator.generate(settings, new GeneratorSeeds(settings.seed()));

        assertThat(summary.employees()).isEqualTo(50);
        assertThat(count("employee")).isEqualTo(50);
        assertThat(count("department")).isEqualTo(8);
        assertThat(count("leave_policy")).isEqualTo(6);
        assertThat(count("expense_policy")).isEqualTo(31);
        assertThat(count("approval_rule")).isEqualTo(12);
        assertThat(count("holiday_calendar")).isEqualTo(15);
        // exactly one CEO, every other employee has a manager, no manager is an employee of a lower level
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE manager_id IS NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM employee e JOIN employee m ON m.employee_id = e.manager_id WHERE m.job_level >= e.job_level
                """, Integer.class)).isZero();
        // every employee has a current primary cost center that matches the history table
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM employee e WHERE e.cost_center_id IS NULL OR NOT EXISTS (
                  SELECT 1 FROM employee_cost_center a WHERE a.employee_id = e.employee_id AND a.assignment_type = 'PRIMARY'
                    AND a.effective_to IS NULL AND a.cost_center_id = e.cost_center_id)
                """, Integer.class)).isZero();
        // finance has several managers able to review expenses
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE designation = 'Finance Manager'", Integer.class)).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE designation = 'VP Finance'", Integer.class)).isEqualTo(1);
        // balances: available is derived, never negative at provisioning
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_balance WHERE available_balance < 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget", Integer.class)).isGreaterThan(100);
    }

    @Test
    void sameSeedProducesIdenticalMasterData() {
        GeneratorSettings settings = new GeneratorSettings(20261022L, SizeProfile.SMALL, LocalDate.of(2026, 10, 22));
        generator.generate(settings, new GeneratorSeeds(settings.seed()));
        String first = fingerprint();
        reset.resetAll();
        generator.generate(settings, new GeneratorSeeds(settings.seed()));
        assertThat(fingerprint()).isEqualTo(first);

        reset.resetAll();
        GeneratorSettings other = new GeneratorSettings(42L, SizeProfile.SMALL, LocalDate.of(2026, 10, 22));
        generator.generate(other, new GeneratorSeeds(other.seed()));
        assertThat(fingerprint()).isNotEqualTo(first);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private String fingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(string_agg(employee_id || email || manager_id_text || country || joining_date::text, ',' ORDER BY employee_id))
                FROM (SELECT employee_id, email, COALESCE(manager_id, '-') AS manager_id_text, country, joining_date FROM employee) t
                """, String.class)
                + jdbc.queryForObject("SELECT md5(string_agg(leave_balance_id || opening_balance::text || carried_forward_balance::text, ',' ORDER BY leave_balance_id)) FROM leave_balance", String.class);
    }
}
