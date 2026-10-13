package br.com.lucraone.pdv.presentation.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.application.sale.ManualPaymentDeclaration;
import br.com.lucraone.pdv.domain.sale.PaymentConfirmationOrigin;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.infrastructure.persistence.LocalDatabaseException;
import br.com.lucraone.pdv.infrastructure.demo.DemoSalesEnvironment;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DemoSalesCartTest {

    @Test void findsFictionalSkuAndBarcodeInDemoCatalog() throws Exception {
        try (DemoSalesEnvironment demo = DemoSalesEnvironment.create()) {
            DemoSalesCart cart = new DemoSalesCart(demo);
            assertEquals("Cafe coado", cart.search("7890000000001").getFirst().name());
            assertEquals("Pao de queijo", cart.search("DEMO-002").getFirst().name());
        }
    }

    @Test void supportsAddQuantityRemoveAndDomainTotalization() throws Exception {
        try (DemoSalesEnvironment demo = DemoSalesEnvironment.create()) {
            DemoSalesCart cart = new DemoSalesCart(demo);
            String coffee = cart.search("DEMO-001").getFirst().id();
            cart.add(coffee);
            cart.add(coffee);
            assertEquals(new BigDecimal("2"), cart.lines().getFirst().quantity());
            cart.setQuantity(coffee, new BigDecimal("1.5"));
            assertEquals("6.75", cart.total().toPlainString());
            cart.remove(coffee);
            assertTrue(cart.isEmpty());
            assertEquals("0.00", cart.total().toPlainString());
        }
    }

    @Test void confirmsCashAndCalculatesChangeThroughDomainService() throws Exception {
        try (DemoSalesEnvironment demo = DemoSalesEnvironment.create()) {
            DemoSalesCart cart = oneCoffee(demo);
            assertEquals("5.50", cart.changeFor("10.00").toPlainString());
            var sale = cart.confirm(PaymentMethod.CASH, "10.00", ManualPaymentDeclaration.CASH_RECEIVED);
            assertEquals("4.50", sale.totalAmount().toPlainString());
            assertEquals("5.50", sale.payment().changeAmount().toPlainString());
            assertEquals(PaymentConfirmationOrigin.MANUAL, sale.payment().confirmationOrigin());
            assertEquals(1, count(demo, "SELECT COUNT(*) FROM pdv_sale_outbox WHERE event_type='SALE_CONFIRMED'"));
            assertEquals(1, count(demo, "SELECT COUNT(*) FROM pdv_manual_payment_attempt WHERE state='COMPLETED'"));
        }
    }

    @Test void recordsManualCardAndPixDeclarationsWithoutProviderAuthorization() throws Exception {
        try (DemoSalesEnvironment demo = DemoSalesEnvironment.create()) {
            DemoSalesCart debitCart = oneCoffee(demo);
            var debit = debitCart.confirm(PaymentMethod.CARD_DEBIT, "4.50",
                    ManualPaymentDeclaration.EXTERNAL_CARD_APPROVED);
            assertEquals(PaymentConfirmationOrigin.MANUAL, debit.payment().confirmationOrigin());

            DemoSalesCart creditCart = oneCoffee(demo);
            var credit = creditCart.confirm(PaymentMethod.CARD_CREDIT, "4.50",
                    ManualPaymentDeclaration.EXTERNAL_CARD_APPROVED);
            assertEquals(PaymentConfirmationOrigin.MANUAL, credit.payment().confirmationOrigin());

            DemoSalesCart pixCart = oneCoffee(demo);
            var pix = pixCart.confirm(PaymentMethod.PIX, "4.50",
                    ManualPaymentDeclaration.PIX_OFFICIAL_CHANNEL_CHECKED);
            assertEquals(PaymentConfirmationOrigin.MANUAL, pix.payment().confirmationOrigin());
            assertEquals(3, count(demo, "SELECT COUNT(*) FROM pdv_sale_outbox WHERE event_type='SALE_CONFIRMED'"));
        }
    }

    @Test void databaseIsTemporaryAndSeparateFromOperationalDataDirectory() throws Exception {
        try (DemoSalesEnvironment demo = DemoSalesEnvironment.create()) {
            String url = demo.connections().jdbcUrl();
            assertTrue(url.startsWith("jdbc:sqlite:" + demo.temporaryRoot().toAbsolutePath()));
            assertTrue(Path.of(url.substring("jdbc:sqlite:".length())).startsWith(demo.temporaryRoot()));
            assertTrue(demo.catalog().findById(demo.catalogContext(), "DEMO-004").isPresent());
        }
    }

    @Test void outboxFailureRollsBackSaleAndRetainsReconciliationRecord() throws Exception {
        try (DemoSalesEnvironment demo = DemoSalesEnvironment.create()) {
            try (Connection connection = demo.connections().openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER demo_reject_outbox BEFORE INSERT ON pdv_sale_outbox "
                        + "BEGIN SELECT RAISE(ABORT, 'demo test failure'); END");
            }
            DemoSalesCart cart = oneCoffee(demo);
            assertThrows(LocalDatabaseException.class,
                    () -> cart.confirm(PaymentMethod.CASH, "4.50", ManualPaymentDeclaration.CASH_RECEIVED));
            assertEquals(0, count(demo, "SELECT COUNT(*) FROM pdv_sale"));
            assertEquals(0, count(demo, "SELECT COUNT(*) FROM pdv_sale_item"));
            assertEquals(0, count(demo, "SELECT COUNT(*) FROM pdv_sale_outbox"));
            assertEquals(1, demo.saleService().listManualPaymentReconciliation().size());
        }
    }

    @Test void manualCashPaymentRejectsInsufficientTender() throws Exception {
        try (DemoSalesEnvironment demo = DemoSalesEnvironment.create()) {
            DemoSalesCart cart = oneCoffee(demo);
            assertThrows(IllegalArgumentException.class, () -> cart.changeFor("4.49"));
            assertThrows(IllegalArgumentException.class,
                    () -> cart.confirm(PaymentMethod.CASH, "4.49", ManualPaymentDeclaration.CASH_RECEIVED));
            assertEquals(0, count(demo, "SELECT COUNT(*) FROM pdv_sale"));
        }
    }

    private static DemoSalesCart oneCoffee(DemoSalesEnvironment demo) {
        DemoSalesCart cart = new DemoSalesCart(demo);
        cart.add(cart.search("DEMO-001").getFirst().id());
        return cart;
    }

    private static int count(DemoSalesEnvironment demo, String sql) throws Exception {
        try (Connection connection = demo.connections().openConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
