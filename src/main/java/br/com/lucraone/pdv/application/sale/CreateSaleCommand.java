package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Sale request contains no prices; the caller-provided UUID is also the provider idempotency key. */
public record CreateSaleCommand(
        UUID saleId,
        List<SaleLineCommand> items,
        Money discountAmount,
        PaymentMethod paymentMethod,
        Money paymentAmount,
        ManualPaymentDeclaration manualDeclaration
) {
    public CreateSaleCommand(UUID saleId, List<SaleLineCommand> items, Money discountAmount,
            PaymentMethod paymentMethod, Money paymentAmount) {
        this(saleId, items, discountAmount, paymentMethod, paymentAmount,
                paymentMethod == PaymentMethod.CASH ? ManualPaymentDeclaration.CASH_RECEIVED : null);
    }
    public CreateSaleCommand {
        Objects.requireNonNull(saleId, "saleId must not be null");
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
        Objects.requireNonNull(discountAmount, "discountAmount must not be null");
        Objects.requireNonNull(paymentMethod, "paymentMethod must not be null");
        Objects.requireNonNull(paymentAmount, "paymentAmount must not be null");
        if (items.isEmpty()) throw new IllegalArgumentException("items must not be empty");
    }
}
