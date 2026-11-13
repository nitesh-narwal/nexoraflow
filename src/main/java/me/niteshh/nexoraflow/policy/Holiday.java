package me.niteshh.nexoraflow.policy;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

@Entity
@Table(name = "holiday")
@Getter
@Setter
@NoArgsConstructor
public class Holiday extends PersistableEntity {

    @Id
    @Column(name = "holiday_id")
    private String holidayId;

    @Column(name = "calendar_id")
    private String calendarId;

    @Column(name = "holiday_date")
    private LocalDate holidayDate;

    @Column(name = "holiday_name")
    private String holidayName;

    @Column(name = "holiday_type")
    private String holidayType = "PUBLIC";

    @Override
    public String getId() {
        return holidayId;
    }
}
