package me.niteshh.nexoraflow.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import me.niteshh.nexoraflow.leave.BusinessDayCalculator;

class BusinessDayCalculatorTest {

    private static final Set<LocalDate> NO_HOLIDAYS = Set.of();

    @Test
    void aFullWorkingWeekHasFiveBusinessDays() {
        // Mon 2026-11-02 .. Fri 2026-11-06
        assertThat(BusinessDayCalculator.countBusinessDays(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6), NO_HOLIDAYS)).isEqualTo(5);
    }

    @Test
    void weekendsAreNotCounted() {
        // Fri 2026-11-06 .. Mon 2026-11-09 spans 4 calendar days but only 2 business days
        LocalDate start = LocalDate.of(2026, 11, 6);
        LocalDate end = LocalDate.of(2026, 11, 9);
        assertThat(end.toEpochDay() - start.toEpochDay() + 1).isEqualTo(4); // the naive (wrong) calculation
        assertThat(BusinessDayCalculator.countBusinessDays(start, end, NO_HOLIDAYS)).isEqualTo(2);
    }

    @Test
    void publicHolidaysOnWeekdaysAreExcluded() {
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 11, 26)); // Thanksgiving (Thursday)
        assertThat(BusinessDayCalculator.countBusinessDays(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 30), holidays)).isEqualTo(3);
        assertThat(BusinessDayCalculator.holidaysOnWeekdays(LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 30), holidays))
                .containsExactly(LocalDate.of(2026, 11, 26));
    }

    @Test
    void aHolidayOnAWeekendDoesNotReduceTheCountTwice() {
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 11, 7)); // Saturday
        assertThat(BusinessDayCalculator.countBusinessDays(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8), holidays)).isEqualTo(5);
        assertThat(BusinessDayCalculator.holidaysOnWeekdays(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8), holidays)).isEmpty();
    }

    @Test
    void rangeThatContainsOnlyNonWorkingDaysHasZeroBusinessDays() {
        assertThat(BusinessDayCalculator.countBusinessDays(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8), NO_HOLIDAYS)).isZero();
        assertThat(BusinessDayCalculator.countBusinessDays(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 25), Set.of(LocalDate.of(2026, 12, 25)))).isZero();
    }

    @Test
    void singleWorkingDayCountsAsOneAndInvertedRangeAsZero() {
        assertThat(BusinessDayCalculator.countBusinessDays(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 3), NO_HOLIDAYS)).isEqualTo(1);
        assertThat(BusinessDayCalculator.countBusinessDays(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 2), NO_HOLIDAYS)).isZero();
    }

    @Test
    void customWeekendDefinitionIsSupported() {
        Set<DayOfWeek> fridaySaturday = EnumSet.of(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY);
        assertThat(BusinessDayCalculator.countBusinessDays(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8), NO_HOLIDAYS, fridaySaturday)).isEqualTo(5);
    }
}
