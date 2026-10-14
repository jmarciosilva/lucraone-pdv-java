package br.com.lucraone.pdv.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.application.catalog.ActiveCatalogRepository;
import br.com.lucraone.pdv.application.catalog.BarcodeResolution;
import br.com.lucraone.pdv.application.catalog.CatalogCacheContext;
import br.com.lucraone.pdv.application.catalog.CatalogPage;
import br.com.lucraone.pdv.application.catalog.CatalogPrice;
import br.com.lucraone.pdv.application.catalog.CatalogProduct;
import br.com.lucraone.pdv.application.sale.AuthorizedSaleContext;
import br.com.lucraone.pdv.application.sale.ElectronicPaymentAuthorizer;
import br.com.lucraone.pdv.application.sale.ManualPaymentDeclaration;
import br.com.lucraone.pdv.application.sale.SaleAuthorizationProvider;
import br.com.lucraone.pdv.application.sale.SaleRepository;
import br.com.lucraone.pdv.application.sale.SaleService;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OperationalSalesCartTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    private FakeSales sales;
    private FakeCatalog catalog;
    private OperationalSalesCart cart;

    private final CatalogProduct italac = product("italac-1", "LEITE-UHT-INTEGRAL-ITALAC-1-LITRO",
            "7898080640017", "LEITE UHT INTEGRAL ITALAC 1 LITRO", "UN", "active", "4.49");

    @BeforeEach
    void setUp() {
        sales = new FakeSales();
        catalog = new FakeCatalog();
        catalog.put(italac);
        cart = new OperationalSalesCart(saleService());
    }

    @Test
    void addsItalacAndComputesTotalOfFourFortyNine() {
        cart.addUnit(italac);

        assertEquals(1, cart.distinctProductCount());
        assertEquals("4.49", cart.total().toPlainString());
        assertEquals("4.49", cart.lines().getFirst().lineTotal().toPlainString());
    }

    @Test
    void addingSameProductIncrementsQuantityAndTotal() {
        cart.addUnit(italac);
        cart.addUnit(italac);

        assertEquals(1, cart.distinctProductCount());
        assertEquals("2.0000", cart.lines().getFirst().quantity().toPlainString());
        assertEquals("8.98", cart.total().toPlainString());
    }

    @Test
    void sumsMultipleProductsWithQuantities() {
        CatalogProduct cafe = product("cafe-1", "SKU-CAFE", "000111", "Café", "UN", "active", "9.50");
        catalog.put(cafe);

        cart.addUnit(italac);
        cart.setQuantity(italac.id(), new BigDecimal("3"));
        cart.addUnit(cafe);

        assertEquals("22.97", cart.total().toPlainString()); // 3 x 4.49 + 1 x 9.50
    }

    @Test
    void setQuantityRejectsNonPositiveOrTooPreciseValues() {
        cart.addUnit(italac);
        assertThrows(IllegalArgumentException.class, () -> cart.setQuantity(italac.id(), new BigDecimal("0")));
        assertThrows(IllegalArgumentException.class, () -> cart.setQuantity(italac.id(), new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> cart.setQuantity(italac.id(), new BigDecimal("1.00001")));
    }

    @Test
    void setQuantityRejectsProductNotInCart() {
        assertThrows(IllegalArgumentException.class, () -> cart.setQuantity("missing", BigDecimal.ONE));
    }

    @Test
    void removeAndClearEmptyTheCart() {
        cart.addUnit(italac);
        cart.remove(italac.id());
        assertTrue(cart.isEmpty());

        cart.addUnit(italac);
        cart.clear();
        assertTrue(cart.isEmpty());
    }

    @Test
    void rejectsInactiveProductOnAdd() {
        CatalogProduct inactive = product("off-1", "SKU-OFF", "000222", "Desativado", "UN", "inactive", "1.00");
        assertThrows(IllegalStateException.class, () -> cart.addUnit(inactive));
    }

    @Test
    void rejectsProductWithoutBrlPriceOnAdd() {
        CatalogProduct usd = new CatalogProduct("usd-1", "SKU-USD", "000333", "Importado", "UN", "active",
                List.of(new CatalogPrice(new BigDecimal("1.00"), "USD")), List.of(), Optional.empty());
        assertThrows(IllegalStateException.class, () -> cart.addUnit(usd));
    }

    @Test
    void checkoutPersistsCashSaleAndClearsCartOnlyAfterSuccess() {
        cart.addUnit(italac);

        Sale sale = cart.checkout(PaymentMethod.CASH, "5.00", ManualPaymentDeclaration.CASH_RECEIVED);

        assertEquals("4.49", sale.totalAmount().toPlainString());
        assertEquals("0.51", sale.payment().changeAmount().toPlainString());
        assertEquals(1, sales.persisted.size());
        assertTrue(cart.isEmpty());
    }

    @Test
    void checkoutPersistsMealVoucherSaleWithManualDeclaration() {
        cart.addUnit(italac);

        Sale sale = cart.checkout(PaymentMethod.MEAL_VOUCHER, "4.49",
                ManualPaymentDeclaration.EXTERNAL_VOUCHER_APPROVED);

        assertEquals(PaymentMethod.MEAL_VOUCHER, sale.payment().method());
        assertEquals("4.49", sale.totalAmount().toPlainString());
        assertTrue(cart.isEmpty());
    }

    @Test
    void checkoutFailureKeepsTheCartIntactForRetry() {
        cart.addUnit(italac);
        sales.failSave = true;

        assertThrows(IllegalStateException.class,
                () -> cart.checkout(PaymentMethod.CASH, "5.00", ManualPaymentDeclaration.CASH_RECEIVED));

        assertFalse(cart.isEmpty());
        assertEquals(1, cart.distinctProductCount());
        assertTrue(sales.persisted.isEmpty());
    }

    @Test
    void checkoutOnEmptyCartIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> cart.checkout(PaymentMethod.CASH, "5.00", ManualPaymentDeclaration.CASH_RECEIVED));
    }

    @Test
    void changeForCashReflectsTheTotal() {
        cart.addUnit(italac);
        assertEquals("0.51", cart.changeFor("5.00").toPlainString());
    }

    private SaleService saleService() {
        AuthorizedSaleContext context = new AuthorizedSaleContext(
                new TerminalContext(
                        new TerminalContext.Terminal("terminal-1", "Caixa", "active", "install-1"),
                        new TerminalContext.Tenant("tenant-1", "Tenant"),
                        new TerminalContext.Company("company-1", "Company"),
                        new TerminalContext.Branch("branch-1", "Branch", "01")),
                new OperatorSessionData("session-1", "active", NOW.plusSeconds(600),
                        "operator-1", "Operador", "operador", "operador@example.test"),
                "BRL");
        SaleAuthorizationProvider authorization = action -> context;
        return new SaleService(sales, catalog, authorization,
                ElectronicPaymentAuthorizer.unavailable(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CatalogProduct product(String id, String sku, String barcode, String name, String unit,
            String status, String price) {
        return new CatalogProduct(id, sku, barcode, name, unit, status,
                List.of(new CatalogPrice(new BigDecimal(price), "BRL")), List.of(), Optional.empty());
    }

    private static final class FakeCatalog implements ActiveCatalogRepository {
        private final Map<String, CatalogProduct> products = new HashMap<>();

        void put(CatalogProduct product) { products.put(product.id(), product); }

        @Override public Optional<CatalogProduct> findById(CatalogCacheContext context, String productId) {
            return Optional.ofNullable(products.get(productId));
        }

        @Override public CatalogPage search(CatalogCacheContext context, String search, int perPage, String cursor) {
            throw new AssertionError("The cart must not drive catalog search during checkout.");
        }

        @Override public BarcodeResolution resolveBarcode(CatalogCacheContext context, String barcode) {
            throw new AssertionError("The cart must not drive barcode resolution during checkout.");
        }
    }

    private static final class FakeSales implements SaleRepository {
        private final Map<UUID, Sale> stored = new HashMap<>();
        private final List<Sale> persisted = new ArrayList<>();
        private boolean failSave;

        @Override public Sale saveConfirmed(Sale sale) {
            if (failSave) throw new IllegalStateException("database failure");
            if (stored.containsKey(sale.saleId())) throw new IllegalStateException("duplicate");
            stored.put(sale.saleId(), sale);
            persisted.add(sale);
            return sale;
        }

        @Override public Optional<Sale> findById(UUID saleId) { return Optional.ofNullable(stored.get(saleId)); }

        @Override public List<Sale> findByTerminalId(String terminalId) {
            return stored.values().stream().filter(s -> s.terminalId().equals(terminalId)).toList();
        }

        @Override public Sale cancel(UUID saleId, String reason, Instant cancelledAt) {
            Sale cancelled = stored.get(saleId).cancel(reason, cancelledAt);
            stored.put(saleId, cancelled);
            return cancelled;
        }
    }
}
