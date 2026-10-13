package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.domain.sale.Money;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/** Product identity and requested quantity/discount; price and descriptive data always come from SQLite. */
public record SaleLineCommand(
        String productId,
        BigDecimal quantity,
        Money discountAmount,
        Optional<String> packageId
) {
    public SaleLineCommand {
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(discountAmount, "discountAmount must not be null");
        Objects.requireNonNull(packageId, "packageId must not be null");
        if (productId.isBlank()) throw new IllegalArgumentException("productId must not be blank");
    }
}
