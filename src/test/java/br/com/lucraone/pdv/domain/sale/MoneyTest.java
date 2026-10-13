package br.com.lucraone.pdv.domain.sale;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Construction and validation
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void zeroAmountIsValid() {
        var m = new Money(BigDecimal.ZERO, "BRL");
        assertEquals("0.00", m.toPlainString());
        assertTrue(m.isZero());
    }

    @Test
    void nullAmountIsRejected() {
        assertThrows(NullPointerException.class, () -> new Money(null, "BRL"));
    }

    @Test
    void nullCurrencyIsRejected() {
        assertThrows(NullPointerException.class, () -> new Money(BigDecimal.ONE, null));
    }

    @Test
    void invalidCurrencyCodeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.ONE, "XX9"));
    }

    @Test
    void negativeAmountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Money(new BigDecimal("-0.01"), "BRL"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rounding — R1: scale 2 HALF_UP storage, scale 4 intermediate (Cenário 6)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void amountIsNormalisedToScale2HalfUp() {
        // 10.335 → 3rd decimal is 5 → HALF_UP rounds up → 10.34
        var m = new Money(new BigDecimal("10.335"), "BRL");
        assertEquals("10.34", m.toPlainString());
    }

    @Test
    void amountRoundsDownWhenLessThanHalf() {
        // 10.334 → 3rd decimal is 4 → HALF_UP rounds down → 10.33
        var m = new Money(new BigDecimal("10.334"), "BRL");
        assertEquals("10.33", m.toPlainString());
    }

    @Test
    void multiplyByQuantityAppliesIntermediateScale4ThenRoundsToScale2() {
        // 10.33 × 1.5: intermediate = 15.4950 (scale 4); stored = 15.50 (scale 2 HALF_UP)
        var price = new Money(new BigDecimal("10.33"), "BRL");
        var result = price.multiplyByQuantity(new BigDecimal("1.5"));
        assertEquals("15.50", result.toPlainString());
    }

    @Test
    void multiplyByQuantityRoundsHalfUpAtScale2() {
        // 10.00 × 0.005 = 0.0500 (intermediate scale 4); stored = 0.05 (scale 2)
        var price = new Money(new BigDecimal("10.00"), "BRL");
        var result = price.multiplyByQuantity(new BigDecimal("0.005"));
        assertEquals("0.05", result.toPlainString());
    }

    @Test
    void multiplyByWholeQuantityProducesExactResult() {
        var price = new Money(new BigDecimal("10.50"), "BRL");
        var result = price.multiplyByQuantity(new BigDecimal("3"));
        assertEquals("31.50", result.toPlainString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Arithmetic (Cenário 3)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void additionWithSameCurrency() {
        var a = new Money(new BigDecimal("10.50"), "BRL");
        var b = new Money(new BigDecimal("5.25"), "BRL");
        assertEquals("15.75", a.add(b).toPlainString());
    }

    @Test
    void additionWithDifferentCurrenciesIsRejected() {
        var brl = new Money(new BigDecimal("10.00"), "BRL");
        var usd = new Money(new BigDecimal("10.00"), "USD");
        assertThrows(IllegalArgumentException.class, () -> brl.add(usd));
    }

    @Test
    void subtractionProducesCorrectResult() {
        var a = new Money(new BigDecimal("20.00"), "BRL");
        var b = new Money(new BigDecimal("5.50"), "BRL");
        assertEquals("14.50", a.subtract(b).toPlainString());
    }

    @Test
    void subtractionResultingInZeroIsValid() {
        var a = new Money(new BigDecimal("10.00"), "BRL");
        var b = new Money(new BigDecimal("10.00"), "BRL");
        assertEquals("0.00", a.subtract(b).toPlainString());
        assertTrue(a.subtract(b).isZero());
    }

    @Test
    void subtractionResultingInNegativeIsRejected() {
        var a = new Money(new BigDecimal("5.00"), "BRL");
        var b = new Money(new BigDecimal("5.01"), "BRL");
        assertThrows(IllegalArgumentException.class, () -> a.subtract(b));
    }

    @Test
    void subtractionWithDifferentCurrenciesIsRejected() {
        var brl = new Money(new BigDecimal("10.00"), "BRL");
        var usd = new Money(new BigDecimal("5.00"), "USD");
        assertThrows(IllegalArgumentException.class, () -> brl.subtract(usd));
    }

    @Test
    void zeroStaticFactoryProducesZeroAtScale2() {
        var z = Money.zero("BRL");
        assertEquals("0.00", z.toPlainString());
        assertTrue(z.isZero());
        assertEquals("BRL", z.currency());
    }
}
