package br.com.lucraone.pdv.domain.sale;

import java.util.Objects;

/**
 * Payment details recorded at the moment of sale confirmation. Validation of payment sufficiency
 * (e.g. {@code paymentAmount >= totalAmount} for cash) is the responsibility of the UI; the domain
 * only stores what was tendered.
 */
public record SalePayment(
        PaymentMethod method,
        Money paymentAmount,
        Money changeAmount,
        PaymentConfirmationOrigin confirmationOrigin
) {

    public SalePayment(PaymentMethod method, Money paymentAmount, Money changeAmount) {
        this(method, paymentAmount, changeAmount, PaymentConfirmationOrigin.LEGACY_UNCLASSIFIED);
    }

    public SalePayment {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(paymentAmount, "paymentAmount must not be null");
        Objects.requireNonNull(changeAmount, "changeAmount must not be null");
        Objects.requireNonNull(confirmationOrigin, "confirmationOrigin must not be null");
        if (!paymentAmount.currency().equals(changeAmount.currency())) {
            throw new IllegalArgumentException(
                    "paymentAmount and changeAmount must use the same currency: "
                    + paymentAmount.currency() + " vs " + changeAmount.currency());
        }
    }
}
