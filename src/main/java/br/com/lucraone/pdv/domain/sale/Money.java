package br.com.lucraone.pdv.domain.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Monetary amount with ISO 4217 currency, always stored at scale 2 (HALF_UP).
 * Intermediate computations in {@link #multiplyByQuantity} use scale 4; the constructor then
 * normalises the result to scale 2, matching the TEXT decimal format in the SQLite schema.
 */
public record Money(BigDecimal amount, String currency) {

    static final int STORAGE_SCALE = 2;
    static final int INTERMEDIATE_SCALE = 4;
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Currency.getInstance(currency); // validates ISO 4217
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        amount = amount.setScale(STORAGE_SCALE, ROUNDING);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * Subtracts {@code other} from this amount.
     *
     * @throws IllegalArgumentException if the result would be negative
     */
    public Money subtract(Money other) {
        requireSameCurrency(other);
        BigDecimal result = amount.subtract(other.amount);
        if (result.signum() < 0) {
            throw new IllegalArgumentException(
                    "subtraction result is negative: " + amount.toPlainString()
                    + " - " + other.amount.toPlainString());
        }
        return new Money(result, currency);
    }

    /**
     * Multiplies this amount by a quantity using scale-4 intermediate precision, then rounds the
     * result to scale 2 via the constructor. Implements R1: escala 4 intermediária, escala 2 HALF_UP
     * para armazenamento.
     *
     * @param quantity positive decimal factor (e.g. "1.5000" kg, "3" units)
     */
    public Money multiplyByQuantity(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "quantity must not be null");
        BigDecimal intermediate = amount.multiply(quantity).setScale(INTERMEDIATE_SCALE, ROUNDING);
        return new Money(intermediate, currency);
    }

    public boolean isZero() {
        return amount.compareTo(BigDecimal.ZERO) == 0;
    }

    /** Returns the decimal string stored in SQLite TEXT columns, e.g. {@code "10.50"}. */
    public String toPlainString() {
        return amount.toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
