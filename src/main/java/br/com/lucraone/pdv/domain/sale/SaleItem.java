package br.com.lucraone.pdv.domain.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable line item within a {@link Sale}. Captures a snapshot of the product and optional
 * package data at the moment of sale confirmation; subsequent catalogue updates do not affect stored
 * items.
 *
 * <p>Use {@link #of} to create instances — it computes {@code totalAmount} from the other monetary
 * fields and normalises {@code quantity} to scale 4. The compact constructor validates structure
 * only; consistency between monetary fields is enforced by {@code of}.
 */
public record SaleItem(
        int lineNumber,
        String productId,
        String productName,
        Optional<String> productSku,
        String productUnit,
        BigDecimal quantity,
        Money unitPrice,
        Money discountAmount,
        Money totalAmount,
        Optional<String> packageId,
        Optional<String> packageName,
        Optional<BigDecimal> packageFactor
) {

    static final int QUANTITY_SCALE = 4;

    public SaleItem {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("lineNumber must be >= 1, got " + lineNumber);
        }
        requireNonBlank(productId, "productId");
        requireNonBlank(productName, "productName");
        productSku = Objects.requireNonNull(productSku, "productSku must not be null");
        requireNonBlank(productUnit, "productUnit");
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        quantity = quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException(
                    "quantity must remain positive at scale " + QUANTITY_SCALE);
        }
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        Objects.requireNonNull(discountAmount, "discountAmount must not be null");
        requireSameCurrency(unitPrice, discountAmount, "unitPrice and discountAmount");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        requireSameCurrency(unitPrice, totalAmount, "unitPrice and totalAmount");
        Money expectedTotal = unitPrice.multiplyByQuantity(quantity).subtract(discountAmount);
        if (expectedTotal.amount().compareTo(totalAmount.amount()) != 0) {
            throw new IllegalArgumentException(
                    "totalAmount does not match quantity, unitPrice and discountAmount");
        }
        packageId = Objects.requireNonNull(packageId, "packageId must not be null");
        packageName = Objects.requireNonNull(packageName, "packageName must not be null");
        packageFactor = Objects.requireNonNull(packageFactor, "packageFactor must not be null");
        packageFactor.ifPresent(f -> {
            if (f.signum() <= 0) {
                throw new IllegalArgumentException("packageFactor must be positive");
            }
        });
    }

    /**
     * Creates a {@code SaleItem} and computes {@code totalAmount} as
     * {@code round(quantity × unitPrice, scale=4) − discountAmount}, rounded to scale 2.
     *
     * @param lineNumber     1-based position within the sale
     * @param quantity       positive decimal amount; stored at scale {@value QUANTITY_SCALE}
     * @param unitPrice      price per unit (or per package when package fields are present)
     * @param discountAmount item-level discount; must not exceed {@code quantity × unitPrice}
     */
    public static SaleItem of(
            int lineNumber,
            String productId,
            String productName,
            Optional<String> productSku,
            String productUnit,
            BigDecimal quantity,
            Money unitPrice,
            Money discountAmount,
            Optional<String> packageId,
            Optional<String> packageName,
            Optional<BigDecimal> packageFactor) {
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");
        Objects.requireNonNull(discountAmount, "discountAmount must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Money itemSubtotal = unitPrice.multiplyByQuantity(quantity);
        Money total = itemSubtotal.subtract(discountAmount);
        return new SaleItem(lineNumber, productId, productName, productSku, productUnit,
                quantity, unitPrice, discountAmount, total, packageId, packageName, packageFactor);
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSameCurrency(Money a, Money b, String context) {
        if (!a.currency().equals(b.currency())) {
            throw new IllegalArgumentException(context + " must use the same currency: "
                    + a.currency() + " vs " + b.currency());
        }
    }
}
