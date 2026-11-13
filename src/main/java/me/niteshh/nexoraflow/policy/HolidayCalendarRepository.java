package me.niteshh.nexoraflow.policy;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayCalendarRepository extends JpaRepository<HolidayCalendar, String> {

    Optional<HolidayCalendar> findByCountryAndCalendarYear(String country, int calendarYear);

    List<HolidayCalendar> findByCountryOrderByCalendarYear(String country);
}
