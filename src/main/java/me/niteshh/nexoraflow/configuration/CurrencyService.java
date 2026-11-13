package me.niteshh.nexoraflow.configuration;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.stereotype.Service;

import me.niteshh.nexoraflow.common.Money;

/**
 * Currency conversion to the reporting currency (USD) using the fixed rates stored in the
 * {@code currency} table. Rates are a documented assumption, not live market data.
 */
@Service
public class CurrencyService {

    private final CurrencyRepository currencies;

    public CurrencyService(CurrencyRepository currencies) {
        this.currencies = currencies;
    }

    public Optional<Currency> find(String code) {
        return code == null ? Optional.empty() : currencies.findById(code);
    }

    public boolean isSupported(String code) {
        return find(code).map(Currency::isSupported).orElse(false);
    }

    /** Converts to the reporting currency; amount is scaled to 2 decimals using HALF_UP. */
    public BigDecimal toReporting(BigDecimal amount, String currencyCode) {
        Currency currency = find(currencyCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown currency " + currencyCode));
        return convert(amount, currency.getRateToReporting());
    }

    public static BigDecimal convert(BigDecimal amount, BigDecimal rateToReporting) {
        return Money.scale(amount.multiply(rateToReporting));
    }

    /** Converts an amount between two currencies via the reporting currency. */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (from.equals(to)) {
            return Money.scale(amount);
        }
        BigDecimal inReporting = toReporting(amount, from);
        Currency target = find(to).orElseThrow(() -> new IllegalArgumentException("Unknown currency " + to));
        return Money.scale(inReporting.divide(target.getRateToReporting(), 6, Money.ROUNDING));
    }
}
