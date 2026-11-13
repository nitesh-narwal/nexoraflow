package me.niteshh.nexoraflow.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

@Entity
@Table(name = "holiday_calendar")
@Getter
@Setter
@NoArgsConstructor
public class HolidayCalendar extends PersistableEntity {

    @Id
    @Column(name = "calendar_id")
    private String calendarId;

    private String country;

    @Column(name = "calendar_year")
    private int calendarYear;

    @Column(name = "calendar_name")
    private String calendarName;

    @Override
    public String getId() {
        return calendarId;
    }
}
