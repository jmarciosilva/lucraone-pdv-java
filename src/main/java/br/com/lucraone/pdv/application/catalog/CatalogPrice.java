package br.com.lucraone.pdv.application.catalog;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record CatalogPrice(BigDecimal amount, String currency) {
    public CatalogPrice {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Currency.getInstance(currency);
        if (amount.signum() < 0) throw new IllegalArgumentException("amount must not be negative");
    }
}
