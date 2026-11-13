package me.niteshh.nexoraflow.configuration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

@Entity
@Table(name = "country")
@Getter
@Setter
@NoArgsConstructor
public class Country extends PersistableEntity {

    @Id
    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "country_name")
    private String countryName;

    @Column(name = "currency_code")
    private String currencyCode;

    @Override
    public String getId() {
        return countryCode;
    }
}
