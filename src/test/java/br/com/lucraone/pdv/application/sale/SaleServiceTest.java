package br.com.lucraone.pdv.application.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.application.catalog.ActiveCatalogRepository;
import br.com.lucraone.pdv.application.catalog.BarcodeResolution;
import br.com.lucraone.pdv.application.catalog.CatalogCacheContext;
import br.com.lucraone.pdv.application.catalog.CatalogPackage;
import br.com.lucraone.pdv.application.catalog.CatalogPage;
import br.com.lucraone.pdv.application.catalog.CatalogPrice;
import br.com.lucraone.pdv.application.catalog.CatalogProduct;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
import br.com.lucraone.pdv.domain.sale.SaleItem;
import br.com.lucraone.pdv.domain.sale.SalePayment;
import br.com.lucraone.pdv.domain.sale.SaleStatus;
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

class SaleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final UUID SALE_ID = UUID.fromString("00000000-0000-4000-8000-000000000211");

    private FakeSales sales;
    private FakeCatalog catalog;
    private AuthorizedSaleContext context;
    private SaleAuthorizationProvider authorization;
    private ElectronicPaymentAuthorizer electronicPayments;
    private SaleService service;

    @BeforeEach
    void setUp() {
        sales = new FakeSales();
        catalog = new FakeCatalog();
        catalog.put(product("p-1", "Café", "active", "9.50"));
        context = context("active", "active");
        authorization = action -> context;
        electronicPayments = ElectronicPaymentAuthorizer.unavailable();
        service = service();
    }

    @Test
    void confirmsCashSaleWithOneItemAndCatalogSnapshot() {
        Sale sale = service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "10.00"));

        assertEquals("9.50", sale.totalAmount().toPlainString());
        assertEquals("0.50", sale.payment().changeAmount().toPlainString());
        assertEquals("Café", sale.items().getFirst().productName());
        assertEquals(1, sales.persisted.size());
    }

    @Test
    void confirmsMultipleItemsAndCalculatesTotalFromCatalogPrices() {
        catalog.put(product("p-2", "Chá", "active", "2.25"));

        Sale sale = service.createConfirmedSale(command(List.of(
                line("p-1", "2", "0.00"), line("p-2", "3", "0.00")), "30.00"));

        assertEquals("25.75", sale.subtotalAmount().toPlainString());
        assertEquals("4.25", sale.payment().changeAmount().toPlainString());
    }

    @Test
    void rejectsInsufficientCashBeforePersistence() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "9.49")));
        assertTrue(sales.persisted.isEmpty());
    }

    @Test
    void computesCashChangeFromReceivedAmount() {
        Sale sale = service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "10.00"));
        assertEquals("0.50", sale.payment().changeAmount().toPlainString());
    }

    @Test
    void rejectsProductMissingFromActiveLocalSnapshot() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createConfirmedSale(command(List.of(line("missing", "1", "0.00")), "10.00")));
    }

    @Test
    void rejectsInactiveProduct() {
        catalog.put(product("p-off", "Desativado", "inactive", "9.50"));
        assertThrows(IllegalStateException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-off", "1", "0.00")), "10.00")));
    }

    @Test
    void rejectsProductWithoutPriceInAuthorizedCurrency() {
        catalog.put(new CatalogProduct("p-usd", "USD", null, "Importado", "UN", "active",
                List.of(new CatalogPrice(new BigDecimal("1.00"), "USD")), List.of(), Optional.empty()));
        assertThrows(IllegalStateException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-usd", "1", "0.00")), "10.00")));
    }

    @Test
    void rejectsInvalidQuantityPrecisionAndNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-1", "0.00001", "0.00")), "1.00")));
        assertThrows(IllegalArgumentException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-1", "-1", "0.00")), "1.00")));
    }

    @Test
    void rejectsWhenAuthorizationProviderHasNoAuthorizedOperator() {
        authorization = action -> { throw new IllegalStateException("operator denied"); };
        service = service();
        assertThrows(IllegalStateException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "10.00")));
    }

    @Test
    void rejectsTerminalThatIsNotActiveEvenIfProviderReturnsContext() {
        context = context("suspended", "active");
        service = service();
        assertThrows(IllegalStateException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "10.00")));
    }

    @Test
    void rejectsExpiredOrInactiveOperatorSession() {
        context = context("active", "inactive");
        service = service();
        assertThrows(IllegalStateException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "10.00")));
    }

    @Test
    void cardWithoutExplicitManualDeclarationIsUnavailable() {
        CreateSaleCommand card = command(PaymentMethod.CARD_CREDIT,
                List.of(line("p-1", "1", "0.00")), "9.50");
        assertThrows(IllegalStateException.class, () -> service.createConfirmedSale(card));
        assertTrue(sales.persisted.isEmpty());
    }

    @Test
    void pixWithoutExplicitManualDeclarationIsUnavailable() {
        CreateSaleCommand pix = command(PaymentMethod.PIX,
                List.of(line("p-1", "1", "0.00")), "9.50");
        assertThrows(IllegalStateException.class, () -> service.createConfirmedSale(pix));
        assertTrue(sales.persisted.isEmpty());
    }

    @Test
    void confirmsCardAndPixThroughExplicitManualDeclarations() {
        CreateSaleCommand card = declaredCommand(PaymentMethod.CARD_DEBIT,
                ManualPaymentDeclaration.EXTERNAL_CARD_APPROVED);
        Sale cardSale = service.createConfirmedSale(card);
        assertEquals(br.com.lucraone.pdv.domain.sale.PaymentConfirmationOrigin.MANUAL,
                cardSale.payment().confirmationOrigin());
        assertEquals(0, electronicPaymentsCalls);

        CreateSaleCommand credit = new CreateSaleCommand(UUID.randomUUID(), List.of(line("p-1", "1", "0.00")),
                money("0.00"), PaymentMethod.CARD_CREDIT, money("9.50"),
                ManualPaymentDeclaration.EXTERNAL_CARD_APPROVED);
        assertEquals(br.com.lucraone.pdv.domain.sale.PaymentConfirmationOrigin.MANUAL,
                service.createConfirmedSale(credit).payment().confirmationOrigin());

        CreateSaleCommand pix = new CreateSaleCommand(UUID.randomUUID(), List.of(line("p-1", "1", "0.00")),
                money("0.00"), PaymentMethod.PIX, money("9.50"),
                ManualPaymentDeclaration.PIX_OFFICIAL_CHANNEL_CHECKED);
        assertEquals(br.com.lucraone.pdv.domain.sale.PaymentConfirmationOrigin.MANUAL,
                service.createConfirmedSale(pix).payment().confirmationOrigin());
        assertEquals(0, electronicPaymentsCalls);
    }

    @Test
    void confirmsMealAndFoodVouchersThroughExplicitManualDeclarations() {
        CreateSaleCommand meal = new CreateSaleCommand(UUID.randomUUID(), List.of(line("p-1", "1", "0.00")),
                money("0.00"), PaymentMethod.MEAL_VOUCHER, money("9.50"),
                ManualPaymentDeclaration.EXTERNAL_VOUCHER_APPROVED);
        Sale mealSale = service.createConfirmedSale(meal);
        assertEquals(PaymentMethod.MEAL_VOUCHER, mealSale.payment().method());
        assertEquals(br.com.lucraone.pdv.domain.sale.PaymentConfirmationOrigin.MANUAL,
                mealSale.payment().confirmationOrigin());

        CreateSaleCommand food = new CreateSaleCommand(UUID.randomUUID(), List.of(line("p-1", "1", "0.00")),
                money("0.00"), PaymentMethod.FOOD_VOUCHER, money("9.50"),
                ManualPaymentDeclaration.EXTERNAL_VOUCHER_APPROVED);
        assertEquals(PaymentMethod.FOOD_VOUCHER, service.createConfirmedSale(food).payment().method());
        assertEquals(0, electronicPaymentsCalls);
    }

    @Test
    void voucherWithoutExplicitManualDeclarationIsUnavailable() {
        CreateSaleCommand meal = command(PaymentMethod.MEAL_VOUCHER, List.of(line("p-1", "1", "0.00")), "9.50");
        assertThrows(IllegalStateException.class, () -> service.createConfirmedSale(meal));
        assertTrue(sales.persisted.isEmpty());
    }

    @Test
    void voucherRequiresPaymentAmountEqualToTotal() {
        CreateSaleCommand meal = new CreateSaleCommand(UUID.randomUUID(), List.of(line("p-1", "1", "0.00")),
                money("0.00"), PaymentMethod.MEAL_VOUCHER, money("10.00"),
                ManualPaymentDeclaration.EXTERNAL_VOUCHER_APPROVED);
        assertThrows(IllegalArgumentException.class, () -> service.createConfirmedSale(meal));
    }

    @Test
    void requestCannotSupplyOrManipulateUnitPrice() {
        Sale sale = service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "9.50"));
        assertEquals("9.50", sale.items().getFirst().unitPrice().toPlainString());
        assertEquals("9.50", sale.totalAmount().toPlainString());
    }

    @Test
    void persistenceFailureIsPropagatedWithoutReportingSuccess() {
        sales.failSave = true;
        assertThrows(IllegalStateException.class,
                () -> service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "9.50")));
    }

    @Test
    void queriesByUuidAndListsOnlyTheCurrentTerminal() {
        Sale created = service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "9.50"));
        assertEquals(Optional.of(created), service.findById(SALE_ID));
        assertEquals(List.of(created), service.listForCurrentTerminal());
        assertFalse(service.findById(UUID.randomUUID()).isPresent());
    }

    @Test
    void cancellationRequiresAuthorizationAndPreservesCommercialHistory() {
        Sale created = service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "9.50"));
        service.cancel(created.saleId(), "Erro de lançamento");
        Sale cancelled = sales.findById(created.saleId()).orElseThrow();
        assertEquals(SaleStatus.CANCELLED, cancelled.status());
        assertEquals(created.items(), cancelled.items());
        assertEquals(created.totalAmount(), cancelled.totalAmount());
    }

    @Test
    void repeatedCancellationIsRejected() {
        Sale created = service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "9.50"));
        service.cancel(created.saleId(), "Motivo");
        assertThrows(IllegalStateException.class, () -> service.cancel(created.saleId(), "Segundo motivo"));
    }

    @Test
    void electronicSaleCancellationIsBlockedUntilFinancialReversalWorkflowExists() {
        SaleItem item = SaleItem.of(1, "p-1", "Café", Optional.empty(), "UN", BigDecimal.ONE,
                money("9.50"), money("0.00"), Optional.empty(), Optional.empty(), Optional.empty());
        Sale electronic = Sale.confirm(SALE_ID, "terminal-1", "tenant-1", "company-1", "branch-1",
                "operator-1", "Operador", "BRL", List.of(item), money("0.00"),
                new SalePayment(PaymentMethod.PIX, money("9.50"), money("0.00")), NOW);
        sales.stored.put(SALE_ID, electronic);

        assertThrows(IllegalStateException.class, () -> service.cancel(SALE_ID, "Cancelamento"));
        assertEquals(SaleStatus.CONFIRMED, sales.findById(SALE_ID).orElseThrow().status());
    }

    @Test
    void cashSaleUsesOnlyLocalCatalogAndPersistencePorts() {
        service.createConfirmedSale(command(List.of(line("p-1", "1", "0.00")), "9.50"));
        assertEquals(1, catalog.lookups);
        assertEquals(1, sales.persisted.size());
        assertEquals(0, electronicPaymentsCalls);
    }

    private int electronicPaymentsCalls;

    private SaleService service() {
        electronicPaymentsCalls = 0;
        return new SaleService(sales, catalog, authorization, (id, method, amount) -> {
            electronicPaymentsCalls++;
            return Optional.empty();
        }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CreateSaleCommand command(List<SaleLineCommand> items, String amount) {
        return command(PaymentMethod.CASH, items, amount);
    }

    private CreateSaleCommand command(PaymentMethod method, List<SaleLineCommand> items, String amount) {
        return new CreateSaleCommand(SALE_ID, items, money("0.00"), method, money(amount));
    }

    private CreateSaleCommand declaredCommand(PaymentMethod method, ManualPaymentDeclaration declaration) {
        return new CreateSaleCommand(SALE_ID, List.of(line("p-1", "1", "0.00")), money("0.00"),
                method, money("9.50"), declaration);
    }

    private SaleLineCommand line(String productId, String quantity, String discount) {
        return new SaleLineCommand(productId, new BigDecimal(quantity), money(discount), Optional.empty());
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), "BRL");
    }

    private CatalogProduct product(String id, String name, String status, String price) {
        return new CatalogProduct(id, "SKU-" + id, null, name, "UN", status,
                List.of(new CatalogPrice(new BigDecimal(price), "BRL")), List.of(), Optional.empty());
    }

    private AuthorizedSaleContext context(String terminalStatus, String operatorStatus) {
        return new AuthorizedSaleContext(
                new TerminalContext(
                        new TerminalContext.Terminal("terminal-1", "Caixa", terminalStatus, "install-1"),
                        new TerminalContext.Tenant("tenant-1", "Tenant"),
                        new TerminalContext.Company("company-1", "Company"),
                        new TerminalContext.Branch("branch-1", "Branch", "01")),
                new OperatorSessionData("session-1", operatorStatus, NOW.plusSeconds(600),
                        "operator-1", "Operador", "operador", "operator@example.test"),
                "BRL");
    }

    private static final class FakeCatalog implements ActiveCatalogRepository {
        private final Map<String, CatalogProduct> products = new HashMap<>();
        private int lookups;

        void put(CatalogProduct product) { products.put(product.id(), product); }

        @Override public Optional<CatalogProduct> findById(CatalogCacheContext context, String productId) {
            lookups++;
            return Optional.ofNullable(products.get(productId));
        }

        @Override public CatalogPage search(CatalogCacheContext context, String search, int perPage, String cursor) {
            throw new AssertionError("SaleService must use exact active-catalog lookup, not search or HTTP.");
        }

        @Override public BarcodeResolution resolveBarcode(CatalogCacheContext context, String barcode) {
            throw new AssertionError("SaleService must not resolve through a remote barcode path.");
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
