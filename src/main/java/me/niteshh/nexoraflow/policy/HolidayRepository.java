package me.niteshh.nexoraflow.policy;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HolidayRepository extends JpaRepository<Holiday, String> {

    @Query("""
            select h.holidayDate from Holiday h, HolidayCalendar c
            where h.calendarId = c.calendarId and c.country = :country
              and h.holidayDate between :from and :to
            order by h.holidayDate
            """)
    List<LocalDate> findHolidayDates(@Param("country") String country, @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);

    List<Holiday> findByCalendarIdOrderByHolidayDate(String calendarId);
}
