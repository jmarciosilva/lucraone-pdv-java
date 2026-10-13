package br.com.lucraone.pdv.domain.sale;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SaleTest {

    private static final String BRL = "BRL";
    private static final UUID SALE_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-10-12T11:00:00Z");

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private Money brl(String amount) {
        return new Money(new BigDecimal(amount), BRL);
    }

    private SaleItem item(int lineNumber, String productId, String productName,
            String quantity, String unitPrice) {
        return SaleItem.of(lineNumber, productId, productName,
                Optional.empty(), "UN", new BigDecimal(quantity),
                brl(unitPrice), brl("0.00"),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private SaleItem itemWithDiscount(int lineNumber, String productId, String productName,
            String quantity, String unitPrice, String discount) {
        return SaleItem.of(lineNumber, productId, productName,
                Optional.empty(), "UN", new BigDecimal(quantity),
                brl(unitPrice), brl(discount),
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private SalePayment cashPayment(String paymentAmount, String changeAmount) {
        return new SalePayment(PaymentMethod.CASH, brl(paymentAmount), brl(changeAmount));
    }

    private Sale confirmedSale(List<SaleItem> items, String discount, String payment, String change) {
        return Sale.confirm(SALE_ID, "terminal-1", "tenant-1", "company-1", "branch-1",
                "operator-1", "João Silva", BRL, items,
                brl(discount), cashPayment(payment, change), CONFIRMED_AT);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1 & 2. Venda válida com um ou múltiplos itens
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void singleItemSaleHasCorrectTotals() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var sale = confirmedSale(items, "0.00", "10.00", "0.00");

        assertEquals(SaleStatus.CONFIRMED, sale.status());
        assertEquals("10.00", sale.subtotalAmount().toPlainString());
        assertEquals("0.00", sale.discountAmount().toPlainString());
        assertEquals("10.00", sale.totalAmount().toPlainString());
        assertEquals(1, sale.items().size());
    }

    @Test
    void multipleItemsSaleHasCorrectTotals() {
        // item1: 2 × 10.00 = 20.00; item2: 3 × 5.50 = 16.50; subtotal = 36.50
        var items = List.of(
                item(1, "prod-1", "Produto A", "2", "10.00"),
                item(2, "prod-2", "Produto B", "3", "5.50")
        );
        var sale = confirmedSale(items, "0.00", "36.50", "0.00");

        assertEquals("20.00", sale.items().get(0).totalAmount().toPlainString());
        assertEquals("16.50", sale.items().get(1).totalAmount().toPlainString());
        assertEquals("36.50", sale.subtotalAmount().toPlainString());
        assertEquals("36.50", sale.totalAmount().toPlainString());
        assertEquals(2, sale.items().size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Cálculos monetários
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void saleLevelDiscountIsSubtractedFromSubtotal() {
        // subtotal = 50.00; sale discount = 5.00; total = 45.00
        var items = List.of(item(1, "prod-1", "Produto A", "5", "10.00"));
        var sale = confirmedSale(items, "5.00", "45.00", "0.00");

        assertEquals("50.00", sale.subtotalAmount().toPlainString());
        assertEquals("5.00", sale.discountAmount().toPlainString());
        assertEquals("45.00", sale.totalAmount().toPlainString());
    }

    @Test
    void itemLevelDiscountReducesItemTotal() {
        // 3 × 10.00 = 30.00 - 3.00 discount = 27.00
        var item = itemWithDiscount(1, "prod-1", "Produto A", "3", "10.00", "3.00");
        assertEquals("27.00", item.totalAmount().toPlainString());
    }

    @Test
    void paymentAmountAndChangeAreStoredAsProvided() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "9.50"));
        var sale = confirmedSale(items, "0.00", "10.00", "0.50");

        assertEquals("10.00", sale.payment().paymentAmount().toPlainString());
        assertEquals("0.50", sale.payment().changeAmount().toPlainString());
        assertEquals(PaymentMethod.CASH, sale.payment().method());
    }

    @Test
    void cashPaymentCannotBeInsufficient() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        assertThrows(IllegalArgumentException.class, () ->
                confirmedSale(items, "0.00", "9.99", "0.00"));
    }

    @Test
    void cashChangeMustEqualTenderMinusSaleTotal() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "9.50"));
        assertThrows(IllegalArgumentException.class, () ->
                confirmedSale(items, "0.00", "10.00", "0.40"));
    }

    @Test
    void nonCashPaymentMustMatchTotalAndHaveNoChange() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        assertThrows(IllegalArgumentException.class, () ->
                Sale.confirm(SALE_ID, "terminal-1", "tenant-1", "company-1", "branch-1",
                        "operator-1", "João Silva", BRL, items, brl("0.00"),
                        new SalePayment(PaymentMethod.PIX, brl("9.00"), brl("0.00")),
                        CONFIRMED_AT));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Quantidades inválidas
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void zeroQuantityIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SaleItem.of(1, "prod-1", "Produto A", Optional.empty(), "UN",
                        BigDecimal.ZERO, brl("10.00"), brl("0.00"),
                        Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void negativeQuantityIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SaleItem.of(1, "prod-1", "Produto A", Optional.empty(), "UN",
                        new BigDecimal("-1"), brl("10.00"), brl("0.00"),
                        Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void positiveQuantityThatRoundsToZeroIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                SaleItem.of(1, "prod-1", "Produto A", Optional.empty(), "KG",
                        new BigDecimal("0.00001"), brl("10.00"), brl("0.00"),
                        Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void zeroLineNumberIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                item(0, "prod-1", "Produto A", "1", "10.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Descontos inválidos
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void itemDiscountExceedingItemSubtotalIsRejected() {
        // 1 × 10.00 = 10.00; discount 10.01 > 10.00 → subtract throws
        assertThrows(IllegalArgumentException.class, () ->
                itemWithDiscount(1, "prod-1", "Produto A", "1", "10.00", "10.01"));
    }

    @Test
    void publicItemConstructorRejectsTotalInconsistentWithPriceAndDiscount() {
        assertThrows(IllegalArgumentException.class, () ->
                new SaleItem(1, "prod-1", "Produto A", Optional.empty(), "UN",
                        BigDecimal.ONE, brl("10.00"), brl("0.00"), brl("9.99"),
                        Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void saleLevelDiscountExceedingSubtotalIsRejected() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        // subtotal = 10.00; sale discount = 10.01 → subtract throws
        assertThrows(IllegalArgumentException.class, () ->
                confirmedSale(items, "10.01", "0.00", "0.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Arredondamento (scale 4 intermediária, scale 2 HALF_UP)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void fractionalQuantityIsRoundedToScale4AndTotalToScale2() {
        // 10.33 × 1.5: intermediate = 15.4950 (scale 4); total = 15.50 (HALF_UP)
        var item = SaleItem.of(1, "prod-1", "Produto A", Optional.empty(), "KG",
                new BigDecimal("1.5"), brl("10.33"), brl("0.00"),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals("1.5000", item.quantity().toPlainString());
        assertEquals("15.50", item.totalAmount().toPlainString());
    }

    @Test
    void multipleItemsWithFractionalQuantitiesAccumulateCorrectly() {
        // item1: 10.33 × 1.5 = 15.50; item2: 10.33 × 1.5 = 15.50; subtotal = 31.00
        var items = List.of(
                SaleItem.of(1, "prod-1", "Produto A", Optional.empty(), "KG",
                        new BigDecimal("1.5"), brl("10.33"), brl("0.00"),
                        Optional.empty(), Optional.empty(), Optional.empty()),
                SaleItem.of(2, "prod-2", "Produto B", Optional.empty(), "KG",
                        new BigDecimal("1.5"), brl("10.33"), brl("0.00"),
                        Optional.empty(), Optional.empty(), Optional.empty())
        );
        var sale = confirmedSale(items, "0.00", "31.00", "0.00");
        assertEquals("31.00", sale.subtotalAmount().toPlainString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Valores negativos
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void negativeUnitPriceIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Money(new BigDecimal("-10.00"), "BRL"));
    }

    @Test
    void negativeDiscountAmountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Money(new BigDecimal("-1.00"), "BRL"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Identificadores obrigatórios
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void nullSaleIdIsRejected() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        assertThrows(NullPointerException.class, () ->
                Sale.confirm(null, "terminal-1", "tenant-1", "company-1", "branch-1",
                        "operator-1", "João", BRL, items, brl("0.00"),
                        cashPayment("10.00", "0.00"), CONFIRMED_AT));
    }

    @Test
    void blankTerminalIdIsRejected() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        assertThrows(IllegalArgumentException.class, () ->
                Sale.confirm(SALE_ID, "  ", "tenant-1", "company-1", "branch-1",
                        "operator-1", "João", BRL, items, brl("0.00"),
                        cashPayment("10.00", "0.00"), CONFIRMED_AT));
    }

    @Test
    void blankOperatorNameIsRejected() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        assertThrows(IllegalArgumentException.class, () ->
                Sale.confirm(SALE_ID, "terminal-1", "tenant-1", "company-1", "branch-1",
                        "operator-1", "", BRL, items, brl("0.00"),
                        cashPayment("10.00", "0.00"), CONFIRMED_AT));
    }

    @Test
    void emptyItemListIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                confirmedSale(List.of(), "0.00", "0.00", "0.00"));
    }

    @Test
    void blankProductIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                item(1, "", "Produto A", "1", "10.00"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Imutabilidade
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void itemListIsUnmodifiable() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var sale = confirmedSale(items, "0.00", "10.00", "0.00");
        assertThrows(UnsupportedOperationException.class,
                () -> sale.items().add(item(2, "prod-2", "Produto B", "1", "5.00")));
    }

    @Test
    void cancelReturnsNewInstanceOriginalUnchanged() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var original = confirmedSale(items, "0.00", "10.00", "0.00");
        var cancelled = original.cancel("Desistência do cliente", CANCELLED_AT);

        assertNotSame(original, cancelled);
        assertEquals(SaleStatus.CONFIRMED, original.status());
        assertTrue(original.cancelledAt().isEmpty());
        assertEquals(SaleStatus.CANCELLED, cancelled.status());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 10. Estados válidos
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void confirmedSaleHasConfirmedStatus() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var sale = confirmedSale(items, "0.00", "10.00", "0.00");
        assertEquals(SaleStatus.CONFIRMED, sale.status());
        assertTrue(sale.cancelledAt().isEmpty());
        assertTrue(sale.cancellationReason().isEmpty());
    }

    @Test
    void saleTotalMismatchWithItemsIsDetectedByConfirm() {
        // Sale.confirm computes subtotal from items; passing wrong discount exposes the check
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        // discount = 100 > subtotal = 10 → subtract throws
        assertThrows(IllegalArgumentException.class, () ->
                Sale.confirm(SALE_ID, "terminal-1", "tenant-1", "company-1", "branch-1",
                        "operator-1", "João", BRL, items, brl("100.00"),
                        cashPayment("0.00", "0.00"), CONFIRMED_AT));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 11. Cancelamento conforme regras aprovadas
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void cancellingAConfirmedSaleTransitionsToCancelled() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var sale = confirmedSale(items, "0.00", "10.00", "0.00").cancel("Erro de lançamento", CANCELLED_AT);

        assertEquals(SaleStatus.CANCELLED, sale.status());
        assertEquals(Optional.of(CANCELLED_AT), sale.cancelledAt());
        assertEquals(Optional.of("Erro de lançamento"), sale.cancellationReason());
    }

    @Test
    void cancelledSalePreservesAllOriginalData() {
        var items = List.of(item(1, "prod-1", "Produto A", "2", "10.00"));
        var original = confirmedSale(items, "0.00", "20.00", "0.00");
        var cancelled = original.cancel("Teste", CANCELLED_AT);

        assertEquals(original.saleId(), cancelled.saleId());
        assertEquals(original.totalAmount().toPlainString(), cancelled.totalAmount().toPlainString());
        assertEquals(original.items(), cancelled.items());
        assertEquals(original.confirmedAt(), cancelled.confirmedAt());
    }

    @Test
    void cancellingAlreadyCancelledSaleIsRejected() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var cancelled = confirmedSale(items, "0.00", "10.00", "0.00")
                .cancel("Motivo inicial", CANCELLED_AT);
        assertThrows(IllegalStateException.class, () ->
                cancelled.cancel("Motivo secundário", CANCELLED_AT));
    }

    @Test
    void blankCancellationReasonIsRejected() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var sale = confirmedSale(items, "0.00", "10.00", "0.00");
        assertThrows(IllegalArgumentException.class, () -> sale.cancel("   ", CANCELLED_AT));
    }

    @Test
    void nullCancelledAtIsRejected() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var sale = confirmedSale(items, "0.00", "10.00", "0.00");
        assertThrows(NullPointerException.class, () -> sale.cancel("Motivo", null));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 12. Preservação dos snapshots comerciais
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void itemSnapshotPreservesProductData() {
        var item = SaleItem.of(1, "prod-abc", "Cerveja Lager 600ml",
                Optional.of("CRV-LGR-600"), "UN",
                new BigDecimal("2"), brl("8.90"), brl("0.00"),
                Optional.empty(), Optional.empty(), Optional.empty());

        assertEquals("prod-abc", item.productId());
        assertEquals("Cerveja Lager 600ml", item.productName());
        assertEquals(Optional.of("CRV-LGR-600"), item.productSku());
        assertEquals("UN", item.productUnit());
        assertEquals("8.90", item.unitPrice().toPlainString());
    }

    @Test
    void itemSnapshotPreservesPackageData() {
        var item = SaleItem.of(1, "prod-1", "Cerveja Caixa 24un",
                Optional.of("CRV-CX24"), "CX",
                new BigDecimal("1"), brl("120.00"), brl("0.00"),
                Optional.of("pkg-001"), Optional.of("Caixa 24un"), Optional.of(new BigDecimal("24")));

        assertEquals(Optional.of("pkg-001"), item.packageId());
        assertEquals(Optional.of("Caixa 24un"), item.packageName());
        assertEquals(Optional.of(new BigDecimal("24")), item.packageFactor());
    }

    @Test
    void itemSnapshotIsIndependentOfExternalMutation() {
        // Items list from the caller is copied by Sale; mutations after creation have no effect.
        var mutableItems = new java.util.ArrayList<SaleItem>();
        mutableItems.add(item(1, "prod-1", "Produto A", "1", "10.00"));
        var sale = Sale.confirm(SALE_ID, "terminal-1", "tenant-1", "company-1", "branch-1",
                "operator-1", "João", BRL, mutableItems, brl("0.00"),
                cashPayment("10.00", "0.00"), CONFIRMED_AT);
        mutableItems.add(item(2, "prod-2", "Produto B", "1", "5.00")); // mutation after confirm
        assertEquals(1, sale.items().size(), "items list must not reflect external mutations");
    }

    @Test
    void saleContextIsPreservedForTraceability() {
        var items = List.of(item(1, "prod-1", "Produto A", "1", "10.00"));
        var sale = Sale.confirm(SALE_ID, "terminal-999", "tenant-abc", "company-xyz", "branch-001",
                "op-007", "Maria Souza", BRL, items, brl("0.00"),
                cashPayment("10.00", "0.00"), CONFIRMED_AT);

        assertEquals("terminal-999", sale.terminalId());
        assertEquals("tenant-abc", sale.tenantId());
        assertEquals("company-xyz", sale.companyId());
        assertEquals("branch-001", sale.branchId());
        assertEquals("op-007", sale.operatorId());
        assertEquals("Maria Souza", sale.operatorName());
        assertEquals(CONFIRMED_AT, sale.confirmedAt());
    }
}
