package me.niteshh.nexoraflow.leave;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pure business-day arithmetic. Leave duration is the number of WORKING days inside the requested
 * range: weekends and the employee's country public holidays are excluded. It is never simply
 * {@code end - start + 1}.
 */
public final class BusinessDayCalculator {

    public static final Set<DayOfWeek> DEFAULT_WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private BusinessDayCalculator() {
    }

    public static boolean isWorkingDay(LocalDate date, Set<LocalDate> holidays, Set<DayOfWeek> weekend) {
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    public static boolean isWorkingDay(LocalDate date, Set<LocalDate> holidays) {
        return isWorkingDay(date, holidays, DEFAULT_WEEKEND);
    }

    /** Working days in the inclusive range, in ascending order. Empty when {@code end < start}. */
    public static List<LocalDate> workingDays(LocalDate start, LocalDate end, Set<LocalDate> holidays, Set<DayOfWeek> weekend) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWorkingDay(d, holidays, weekend)) {
                days.add(d);
            }
        }
        return days;
    }

    public static int countBusinessDays(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        return workingDays(start, end, holidays, DEFAULT_WEEKEND).size();
    }

    public static int countBusinessDays(LocalDate start, LocalDate end, Set<LocalDate> holidays, Set<DayOfWeek> weekend) {
        return workingDays(start, end, holidays, weekend).size();
    }

    /** Public holidays that fall on a weekday inside the range (those that actually reduce the count). */
    public static List<LocalDate> holidaysOnWeekdays(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        List<LocalDate> result = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (holidays.contains(d) && !DEFAULT_WEEKEND.contains(d.getDayOfWeek())) {
                result.add(d);
            }
        }
        return result;
    }
}
