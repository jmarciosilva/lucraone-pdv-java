package br.com.lucraone.pdv.presentation.demo;

import br.com.lucraone.pdv.application.catalog.CatalogProduct;
import br.com.lucraone.pdv.application.catalog.CatalogPrice;
import br.com.lucraone.pdv.application.catalog.CatalogPage;
import br.com.lucraone.pdv.application.sale.CreateSaleCommand;
import br.com.lucraone.pdv.application.sale.ManualPaymentDeclaration;
import br.com.lucraone.pdv.application.sale.SaleLineCommand;
import br.com.lucraone.pdv.application.sale.SaleService;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
import br.com.lucraone.pdv.domain.sale.SaleItem;
import br.com.lucraone.pdv.infrastructure.demo.DemoSalesEnvironment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Small, testable cart facade; monetary calculations reuse the sale domain types. */
public final class DemoSalesCart {
    private static final String CURRENCY = "BRL";
    private final DemoSalesEnvironment environment;
    private final Map<String, CartLine> lines = new LinkedHashMap<>();

    public DemoSalesCart(DemoSalesEnvironment environment) { this.environment = environment; }

    public List<CatalogProduct> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        CatalogPage result = environment.catalog().search(environment.catalogContext(), query.strip(), 50, null);
        return result.products();
    }

    public void add(String productId) {
        CatalogProduct product = environment.catalog().findById(environment.catalogContext(), productId)
                .orElseThrow(() -> new IllegalArgumentException("Produto não encontrado no catálogo DEMO."));
        if (!"active".equalsIgnoreCase(product.status()))
            throw new IllegalStateException("Produto indisponível no catálogo DEMO.");
        price(product); // Fail early if this product cannot be priced in the demo currency.
        CartLine current = lines.get(productId);
        lines.put(productId, new CartLine(product, current == null ? BigDecimal.ONE : current.quantity().add(BigDecimal.ONE)));
    }

    public void setQuantity(String productId, BigDecimal quantity) {
        if (!lines.containsKey(productId)) throw new IllegalArgumentException("Produto não está no carrinho.");
        if (quantity == null || quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > 4)
            throw new IllegalArgumentException("Quantidade deve ser positiva e ter até quatro casas decimais.");
        lines.put(productId, new CartLine(lines.get(productId).product(), quantity));
    }

    public void remove(String productId) { lines.remove(productId); }
    public List<CartLine> lines() { return List.copyOf(lines.values()); }
    public boolean isEmpty() { return lines.isEmpty(); }

    public Money total() {
        return itemSnapshots().stream().map(SaleItem::totalAmount).reduce(Money.zero(CURRENCY), Money::add);
    }

    public Money changeFor(String tendered) {
        Money amount = new Money(new BigDecimal(tendered), CURRENCY);
        return amount.subtract(total());
    }

    public Sale confirm(PaymentMethod method, String amount, ManualPaymentDeclaration declaration) {
        if (lines.isEmpty()) throw new IllegalStateException("Adicione ao menos um produto.");
        List<SaleLineCommand> requested = lines.values().stream()
                .map(line -> new SaleLineCommand(line.product().id(), line.quantity(), Money.zero(CURRENCY), Optional.empty()))
                .toList();
        Sale sale = environment.saleService().createConfirmedSale(new CreateSaleCommand(
                UUID.randomUUID(), requested, Money.zero(CURRENCY), method,
                new Money(new BigDecimal(amount), CURRENCY), declaration));
        lines.clear();
        return sale;
    }

    private List<SaleItem> itemSnapshots() {
        List<SaleItem> result = new ArrayList<>();
        int lineNo = 1;
        for (CartLine line : lines.values()) {
            CatalogProduct product = line.product();
            result.add(SaleItem.of(lineNo++, product.id(), product.name(), Optional.ofNullable(product.sku()),
                    product.unit(), line.quantity(), price(product), Money.zero(CURRENCY),
                    Optional.empty(), Optional.empty(), Optional.empty()));
        }
        return List.copyOf(result);
    }

    private static Money price(CatalogProduct product) {
        CatalogPrice price = product.salePrices().stream().filter(p -> CURRENCY.equals(p.currency())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Produto sem preço DEMO em BRL."));
        return new Money(price.amount().setScale(2, RoundingMode.HALF_UP), CURRENCY);
    }

    public record CartLine(CatalogProduct product, BigDecimal quantity) { }
}
