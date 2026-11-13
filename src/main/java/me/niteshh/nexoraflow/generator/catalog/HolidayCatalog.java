package me.niteshh.nexoraflow.generator.catalog;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Illustrative public-holiday calendars for the fictional company's five countries. They are plausible, not
 * authoritative (documented in docs/assumptions.md). Fixed-date holidays are observed on the nearest weekday
 * where the country does so; movable feasts use the Easter computus; a few lunar holidays are listed for 2026 only.
 */
public final class HolidayCatalog {

    public record NamedHoliday(LocalDate date, String name) {
    }

    private HolidayCatalog() {
    }

    public static List<NamedHoliday> holidays(String country, int year) {
        Map<LocalDate, String> days = new TreeMap<>();
        LocalDate easter = easter(year);
        switch (country) {
            case "US" -> {
                put(days, observedUs(LocalDate.of(year, 1, 1)), "New Year's Day");
                put(days, nth(year, 1, DayOfWeek.MONDAY, 3), "Martin Luther King Jr. Day");
                put(days, nth(year, 2, DayOfWeek.MONDAY, 3), "Presidents' Day");
                put(days, LocalDate.of(year, 5, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), "Memorial Day");
                put(days, observedUs(LocalDate.of(year, 6, 19)), "Juneteenth");
                put(days, observedUs(LocalDate.of(year, 7, 4)), "Independence Day");
                put(days, nth(year, 9, DayOfWeek.MONDAY, 1), "Labor Day");
                put(days, nth(year, 11, DayOfWeek.THURSDAY, 4), "Thanksgiving");
                put(days, observedUs(LocalDate.of(year, 12, 25)), "Christmas Day");
            }
            case "GB" -> {
                put(days, observedGb(LocalDate.of(year, 1, 1)), "New Year's Day");
                put(days, easter.minusDays(2), "Good Friday");
                put(days, easter.plusDays(1), "Easter Monday");
                put(days, nth(year, 5, DayOfWeek.MONDAY, 1), "Early May Bank Holiday");
                put(days, LocalDate.of(year, 5, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), "Spring Bank Holiday");
                put(days, LocalDate.of(year, 8, 31).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)), "Summer Bank Holiday");
                put(days, observedGb(LocalDate.of(year, 12, 25)), "Christmas Day");
                LocalDate boxing = LocalDate.of(year, 12, 26);
                put(days, boxing.getDayOfWeek() == DayOfWeek.SATURDAY ? boxing.plusDays(2)
                        : boxing.getDayOfWeek() == DayOfWeek.SUNDAY ? boxing.plusDays(2)
                        : boxing.equals(observedGb(LocalDate.of(year, 12, 25))) ? boxing.plusDays(1) : boxing, "Boxing Day");
            }
            case "DE" -> {
                put(days, LocalDate.of(year, 1, 1), "New Year's Day");
                put(days, easter.minusDays(2), "Good Friday");
                put(days, easter.plusDays(1), "Easter Monday");
                put(days, LocalDate.of(year, 5, 1), "Labour Day");
                put(days, easter.plusDays(39), "Ascension Day");
                put(days, easter.plusDays(50), "Whit Monday");
                put(days, LocalDate.of(year, 10, 3), "German Unity Day");
                put(days, LocalDate.of(year, 12, 25), "Christmas Day");
                put(days, LocalDate.of(year, 12, 26), "Second Day of Christmas");
            }
            case "IN" -> {
                put(days, LocalDate.of(year, 1, 26), "Republic Day");
                put(days, easter.minusDays(2), "Good Friday");
                put(days, LocalDate.of(year, 8, 15), "Independence Day");
                put(days, LocalDate.of(year, 10, 2), "Gandhi Jayanti");
                put(days, LocalDate.of(year, 12, 25), "Christmas Day");
                if (year == 2026) {
                    put(days, LocalDate.of(2026, 3, 4), "Holi");
                    put(days, LocalDate.of(2026, 10, 20), "Dussehra");
                    put(days, LocalDate.of(2026, 11, 9), "Diwali (Lakshmi Puja)");
                }
            }
            case "SG" -> {
                put(days, LocalDate.of(year, 1, 1), "New Year's Day");
                put(days, easter.minusDays(2), "Good Friday");
                put(days, LocalDate.of(year, 5, 1), "Labour Day");
                put(days, LocalDate.of(year, 8, 9).getDayOfWeek() == DayOfWeek.SUNDAY ? LocalDate.of(year, 8, 10) : LocalDate.of(year, 8, 9), "National Day");
                put(days, observedUs(LocalDate.of(year, 12, 25)).equals(LocalDate.of(year, 12, 25)) ? LocalDate.of(year, 12, 25)
                        : LocalDate.of(year, 12, 25).getDayOfWeek() == DayOfWeek.SUNDAY ? LocalDate.of(year, 12, 26) : LocalDate.of(year, 12, 25), "Christmas Day");
                if (year == 2026) {
                    put(days, LocalDate.of(2026, 2, 17), "Chinese New Year");
                    put(days, LocalDate.of(2026, 2, 18), "Chinese New Year (day 2)");
                    put(days, LocalDate.of(2026, 3, 21), "Hari Raya Puasa");
                    put(days, LocalDate.of(2026, 6, 1), "Vesak Day (observed)");
                    put(days, LocalDate.of(2026, 11, 9), "Deepavali (observed)");
                }
            }
            default -> throw new IllegalArgumentException("No holiday catalog for country " + country);
        }
        List<NamedHoliday> result = new ArrayList<>();
        days.forEach((d, n) -> result.add(new NamedHoliday(d, n)));
        result.sort(Comparator.comparing(NamedHoliday::date));
        return result;
    }

    private static void put(Map<LocalDate, String> days, LocalDate date, String name) {
        days.putIfAbsent(date, name);
    }

    private static LocalDate nth(int year, int month, DayOfWeek day, int n) {
        return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day));
    }

    /** US federal "observed" rule: Saturday -> Friday, Sunday -> Monday. */
    static LocalDate observedUs(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case SATURDAY -> d.minusDays(1);
            case SUNDAY -> d.plusDays(1);
            default -> d;
        };
    }

    /** UK substitute-day rule: a holiday on a weekend moves to the following Monday. */
    static LocalDate observedGb(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case SATURDAY -> d.plusDays(2);
            case SUNDAY -> d.plusDays(1);
            default -> d;
        };
    }

    /** Easter Sunday (Gregorian), anonymous algorithm (Meeus/Jones/Butcher). */
    public static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
