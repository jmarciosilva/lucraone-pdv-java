package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.catalog.CatalogPrice;
import br.com.lucraone.pdv.application.catalog.CatalogProduct;
import br.com.lucraone.pdv.application.sale.CreateSaleCommand;
import br.com.lucraone.pdv.application.sale.ManualPaymentDeclaration;
import br.com.lucraone.pdv.application.sale.SaleLineCommand;
import br.com.lucraone.pdv.application.sale.SaleService;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
import br.com.lucraone.pdv.domain.sale.SaleItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory, pre-confirmation cart for the operational cashier. It holds catalog products the operator
 * selected and computes line/total values with the same {@link SaleItem#of} rounding the persisted sale
 * uses, so the displayed total equals the stored total.
 *
 * <p>This is UI state only: it has no JavaFX dependency and is fully testable. Monetary authority still
 * belongs to {@link SaleService}, which re-resolves prices from the active local catalog at checkout.
 * The cart is cleared only after a sale is persisted successfully.
 */
public final class OperationalSalesCart {

    private static final String CURRENCY = "BRL";

    private final SaleService saleService;
    private final Map<String, CartLine> lines = new LinkedHashMap<>();

    public OperationalSalesCart(SaleService saleService) {
        this.saleService = Objects.requireNonNull(saleService, "saleService must not be null");
    }

    /** Adds one unit of the product, incrementing the quantity if it is already in the cart. */
    public CartLine addUnit(CatalogProduct product) {
        Objects.requireNonNull(product, "product must not be null");
        if (!"active".equalsIgnoreCase(product.status())) {
            throw new IllegalStateException("Produto indisponível no catálogo ativo: " + product.id());
        }
        price(product); // fail early if the product has no price in the operational currency
        CartLine current = lines.get(product.id());
        BigDecimal quantity = current == null ? BigDecimal.ONE : current.quantity().add(BigDecimal.ONE);
        CartLine line = lineFor(product, quantity);
        lines.put(product.id(), line);
        return line;
    }

    public void setQuantity(String productId, BigDecimal quantity) {
        CartLine current = lines.get(productId);
        if (current == null) {
            throw new IllegalArgumentException("Produto não está no carrinho.");
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 4) {
            throw new IllegalArgumentException("Quantidade deve ser positiva e ter até quatro casas decimais.");
        }
        lines.put(productId, lineFor(current.product(), quantity));
    }

    public void remove(String productId) {
        lines.remove(productId);
    }

    public void clear() {
        lines.clear();
    }

    public List<CartLine> lines() {
        return List.copyOf(lines.values());
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int distinctProductCount() {
        return lines.size();
    }

    public Money subtotal() {
        return snapshots().stream().map(SaleItem::totalAmount).reduce(Money.zero(CURRENCY), Money::add);
    }

    /** No sale-level discount is captured in this phase, so the total equals the subtotal. */
    public Money total() {
        return subtotal();
    }

    /** Cash change for a tendered amount; throws if the tender is below the total. */
    public Money changeFor(String tendered) {
        return new Money(new BigDecimal(tendered), CURRENCY).subtract(total());
    }

    /**
     * Confirms and persists the sale through {@link SaleService}. The cart is cleared only after the
     * sale is persisted successfully; any failure leaves the cart intact so the operator can retry.
     *
     * @param paymentAmount cash tendered (cash) or the exact total (card/Pix), as a decimal string
     */
    public Sale checkout(PaymentMethod method, String paymentAmount, ManualPaymentDeclaration declaration) {
        if (lines.isEmpty()) {
            throw new IllegalStateException("Adicione ao menos um produto antes de finalizar a venda.");
        }
        List<SaleLineCommand> requested = lines.values().stream()
                .map(line -> new SaleLineCommand(line.product().id(), line.quantity(),
                        Money.zero(CURRENCY), Optional.empty()))
                .toList();
        Sale sale = saleService.createConfirmedSale(new CreateSaleCommand(
                UUID.randomUUID(), requested, Money.zero(CURRENCY), method,
                new Money(new BigDecimal(paymentAmount), CURRENCY), declaration));
        lines.clear();
        return sale;
    }

    private CartLine lineFor(CatalogProduct product, BigDecimal quantity) {
        Money unitPrice = price(product);
        SaleItem snapshot = snapshot(1, product, quantity);
        return new CartLine(product, snapshot.quantity(), unitPrice, snapshot.totalAmount());
    }

    private List<SaleItem> snapshots() {
        List<SaleItem> result = new ArrayList<>(lines.size());
        int lineNumber = 1;
        for (CartLine line : lines.values()) {
            result.add(snapshot(lineNumber++, line.product(), line.quantity()));
        }
        return List.copyOf(result);
    }

    private SaleItem snapshot(int lineNumber, CatalogProduct product, BigDecimal quantity) {
        return SaleItem.of(lineNumber, product.id(), product.name(), Optional.ofNullable(product.sku()),
                product.unit(), quantity, price(product), Money.zero(CURRENCY),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static Money price(CatalogProduct product) {
        CatalogPrice price = product.salePrices().stream()
                .filter(candidate -> CURRENCY.equals(candidate.currency()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Produto sem preço de venda em " + CURRENCY + ": " + product.id()));
        return new Money(price.amount(), CURRENCY);
    }

    /** A cart line with the computed unit price and line total at the active catalog snapshot. */
    public record CartLine(CatalogProduct product, BigDecimal quantity, Money unitPrice, Money lineTotal) {
        public CartLine {
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(quantity, "quantity must not be null");
            Objects.requireNonNull(unitPrice, "unitPrice must not be null");
            Objects.requireNonNull(lineTotal, "lineTotal must not be null");
        }
    }
}
