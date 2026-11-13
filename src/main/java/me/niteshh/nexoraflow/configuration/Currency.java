package me.niteshh.nexoraflow.configuration;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.niteshh.nexoraflow.common.PersistableEntity;

/** Supported currency with its fixed, documented conversion rate to the reporting currency. */
@Entity
@Table(name = "currency")
@Getter
@Setter
@NoArgsConstructor
public class Currency extends PersistableEntity {

    @Id
    @Column(name = "currency_code")
    private String currencyCode;

    @Column(name = "currency_name")
    private String currencyName;

    @Column(name = "rate_to_reporting")
    private BigDecimal rateToReporting;

    @Column(name = "rate_effective_from")
    private LocalDate rateEffectiveFrom;

    @Column(name = "rate_source")
    private String rateSource;

    private boolean supported = true;

    @Override
    public String getId() {
        return currencyCode;
    }
}
