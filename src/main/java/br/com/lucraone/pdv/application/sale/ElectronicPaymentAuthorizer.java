package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import java.util.Optional;
import java.util.UUID;

/** Adapter port for a real, verifiable electronic payment authorization. */
@FunctionalInterface
public interface ElectronicPaymentAuthorizer {

    Optional<AuthorizedElectronicPayment> authorize(UUID saleId, PaymentMethod method, Money amount);

    static ElectronicPaymentAuthorizer unavailable() {
        return (saleId, method, amount) -> Optional.empty();
    }
}
