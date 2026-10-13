package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import java.time.Instant;
import java.util.Objects;

/** Verified provider result. It is transient in this phase because V7 has no authorization fields. */
public record AuthorizedElectronicPayment(
        PaymentMethod method,
        Money amount,
        String providerId,
        String providerReference,
        Instant authorizedAt
) {
    public AuthorizedElectronicPayment {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(providerReference, "providerReference must not be null");
        Objects.requireNonNull(authorizedAt, "authorizedAt must not be null");
        if (providerId.isBlank() || providerReference.isBlank()) {
            throw new IllegalArgumentException("provider identity and reference must not be blank");
        }
    }
}
