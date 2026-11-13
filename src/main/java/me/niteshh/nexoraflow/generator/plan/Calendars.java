package me.niteshh.nexoraflow.generator.plan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** The generator's own business-day arithmetic (deliberately not shared with the leave engine). */
public final class Calendars {

    private Calendars() {
    }

    public static boolean isWorkingDay(LocalDate d, Set<LocalDate> holidays) {
        DayOfWeek w = d.getDayOfWeek();
        return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !holidays.contains(d);
    }

    public static int businessDays(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        int n = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWorkingDay(d, holidays)) {
                n++;
            }
        }
        return n;
    }

    /** First working day on or after the date. */
    public static LocalDate nextWorkingDay(LocalDate d, Set<LocalDate> holidays) {
        LocalDate x = d;
        while (!isWorkingDay(x, holidays)) {
            x = x.plusDays(1);
        }
        return x;
    }

    /** End date of a leave starting on a working day that spans exactly {@code days} working days. */
    public static LocalDate endAfterWorkingDays(LocalDate start, int days, Set<LocalDate> holidays) {
        LocalDate x = start;
        int counted = isWorkingDay(x, holidays) ? 1 : 0;
        while (counted < days) {
            x = x.plusDays(1);
            if (isWorkingDay(x, holidays)) {
                counted++;
            }
        }
        return x;
    }
}
