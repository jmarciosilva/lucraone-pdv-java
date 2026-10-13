package br.com.lucraone.pdv.application.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.application.catalog.CatalogCacheContext;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcActiveCatalogRepository;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcSaleRepository;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcTransactionManager;
import br.com.lucraone.pdv.infrastructure.persistence.LocalDataDirectory;
import br.com.lucraone.pdv.infrastructure.persistence.LocalDatabaseInitializer;
import br.com.lucraone.pdv.infrastructure.persistence.SqliteConnectionFactory;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end application-to-SQLite coverage using only synthetic local data. */
class SaleServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");
    private static final CatalogCacheContext CATALOG_CONTEXT =
            new CatalogCacheContext("terminal-1", "tenant-1", "company-1");

    @TempDir
    Path temporaryDirectory;

    private SqliteConnectionFactory connections;
    private JdbcSaleRepository sales;
    private JdbcActiveCatalogRepository catalog;
    private SaleService service;

    @BeforeEach
    void setUp() throws SQLException {
        LocalDataDirectory directory = new LocalDataDirectory(
                new WindowsPlatformPaths(key -> temporaryDirectory.toString()));
        connections = new LocalDatabaseInitializer(directory).initialize();
        JdbcTransactionManager transactions = new JdbcTransactionManager(connections);
        sales = new JdbcSaleRepository(transactions);
        catalog = new JdbcActiveCatalogRepository(transactions);
        installCatalogVersion(1, "prod-1", "Café", "12.50");

        AuthorizedSaleContext context = new AuthorizedSaleContext(
                new TerminalContext(
                        new TerminalContext.Terminal("terminal-1", "Caixa", "active", "install-1"),
                        new TerminalContext.Tenant("tenant-1", "Tenant"),
                        new TerminalContext.Company("company-1", "Empresa"),
                        new TerminalContext.Branch("branch-1", "Loja", "01")),
                new OperatorSessionData("session-1", "active", NOW.plusSeconds(3600),
                        "operator-1", "Operador", "operador", "operator@example.test"), "BRL");
        service = new SaleService(sales, catalog, action -> context,
                ElectronicPaymentAuthorizer.unavailable(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void confirmsFromActiveSqliteCatalogAndPersistsSalePlusOutboxWithoutHttp() {
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000301");
        CreateSaleCommand command = new CreateSaleCommand(id,
                List.of(new SaleLineCommand("prod-1", BigDecimal.ONE, money("0.00"), Optional.empty())),
                money("0.00"), PaymentMethod.CASH, money("20.00"));

        var created = service.createConfirmedSale(command);

        assertEquals("12.50", created.items().getFirst().unitPrice().toPlainString());
        assertEquals("7.50", created.payment().changeAmount().toPlainString());
        assertEquals(Optional.of(created), service.findById(id));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM pdv_sale WHERE sale_id='" + id + "'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM pdv_sale_item WHERE sale_id='" + id + "'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM pdv_sale_outbox WHERE sale_id='" + id + "'"));
        assertEquals("MANUAL", scalar("SELECT payment_confirmation_origin FROM pdv_sale WHERE sale_id='" + id + "'"));
        assertEquals("COMPLETED", scalar("SELECT state FROM pdv_manual_payment_attempt WHERE sale_id='" + id + "'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM pdv_manual_payment_attempt_item WHERE confirmation_id = "
                + "(SELECT payment_confirmation_id FROM pdv_sale WHERE sale_id='" + id + "')"));
    }

    @Test
    void repeatedRequestWithSameSaleIdIsIdempotent() {
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000311");
        CreateSaleCommand command = new CreateSaleCommand(id,
                List.of(new SaleLineCommand("prod-1", BigDecimal.ONE, money("0.00"), Optional.empty())),
                money("0.00"), PaymentMethod.CASH, money("12.50"));
        var first = service.createConfirmedSale(command);
        var retried = service.createConfirmedSale(command);
        assertEquals(first, retried);
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM pdv_sale WHERE sale_id='" + id + "'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM pdv_manual_payment_attempt WHERE sale_id='" + id + "'"));
        assertEquals(1, scalarInt("SELECT COUNT(*) FROM pdv_sale_outbox WHERE sale_id='" + id + "'"));
    }

    @Test
    void outboxFailureLeavesDeclaredPaymentForReconciliationWithoutSale() throws SQLException {
        try (Connection connection = connections.openConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_sale_outbox BEFORE INSERT ON pdv_sale_outbox "
                    + "BEGIN SELECT RAISE(ABORT, 'outbox failure'); END");
        }
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000312");
        CreateSaleCommand command = new CreateSaleCommand(id,
                List.of(new SaleLineCommand("prod-1", BigDecimal.ONE, money("0.00"), Optional.empty())),
                money("0.00"), PaymentMethod.CASH, money("12.50"));
        assertThrows(RuntimeException.class, () -> service.createConfirmedSale(command));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM pdv_sale WHERE sale_id='" + id + "'"));
        assertEquals("RECONCILIATION_REQUIRED", scalar("SELECT state FROM pdv_manual_payment_attempt WHERE sale_id='" + id + "'"));
        assertEquals(1, service.listManualPaymentReconciliation().size());
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM pdv_sale_outbox WHERE sale_id='" + id + "'"));
    }

    @Test
    void operatorMustHaveSpecificManualPaymentPermission() {
        service = new SaleService(sales, catalog, action -> {
            if (action == SaleAuthorizationAction.CONFIRM_MANUAL_PAYMENT)
                throw new IllegalStateException("manual payment permission missing");
            return new AuthorizedSaleContext(
                    new TerminalContext(new TerminalContext.Terminal("terminal-1", "Caixa", "active", "install-1"),
                            new TerminalContext.Tenant("tenant-1", "Tenant"),
                            new TerminalContext.Company("company-1", "Empresa"),
                            new TerminalContext.Branch("branch-1", "Loja", "01")),
                    new OperatorSessionData("session-1", "active", NOW.plusSeconds(3600),
                            "operator-1", "Operador", "operador", "operator@example.test"), "BRL");
        }, ElectronicPaymentAuthorizer.unavailable(), Clock.fixed(NOW, ZoneOffset.UTC));
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000313");
        CreateSaleCommand command = new CreateSaleCommand(id,
                List.of(new SaleLineCommand("prod-1", BigDecimal.ONE, money("0.00"), Optional.empty())),
                money("0.00"), PaymentMethod.CASH, money("12.50"));
        assertThrows(IllegalStateException.class, () -> service.createConfirmedSale(command));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM pdv_sale"));
    }

    @Test
    void newCatalogVersionDoesNotChangeExistingSaleSnapshot() throws SQLException {
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000302");
        CreateSaleCommand command = new CreateSaleCommand(id,
                List.of(new SaleLineCommand("prod-1", BigDecimal.ONE, money("0.00"), Optional.empty())),
                money("0.00"), PaymentMethod.CASH, money("12.50"));
        service.createConfirmedSale(command);

        installCatalogVersion(2, "prod-1", "Café atualizado", "20.00");

        var historical = sales.findById(id).orElseThrow();
        assertEquals("Café", historical.items().getFirst().productName());
        assertEquals("12.50", historical.items().getFirst().unitPrice().toPlainString());
    }

    @Test
    void rejectsPriceFromCatalogWhenActiveVersionHasNoPrice() throws SQLException {
        installCatalogVersion(2, "prod-1", "Café", null);
        UUID id = UUID.fromString("00000000-0000-4000-8000-000000000303");
        CreateSaleCommand command = new CreateSaleCommand(id,
                List.of(new SaleLineCommand("prod-1", BigDecimal.ONE, money("0.00"), Optional.empty())),
                money("0.00"), PaymentMethod.CASH, money("1.00"));

        assertThrows(IllegalStateException.class, () -> service.createConfirmedSale(command));
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM pdv_sale WHERE sale_id='" + id + "'"));
    }

    private void installCatalogVersion(long version, String productId, String name, String price) throws SQLException {
        try (Connection connection = connections.openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO pdv_catalog_snapshot_version
                            (terminal_id, tenant_id, company_id, version, delivery_id, checksum,
                             product_count, price_count, package_count, applied_at)
                        VALUES ('terminal-1','tenant-1','company-1',?,'delivery-' || ?, 'checksum',1,?,0,?)
                        """)) {
                    insert.setLong(1, version);
                    insert.setLong(2, version);
                    insert.setInt(3, price == null ? 0 : 1);
                    insert.setString(4, NOW.toString());
                    insert.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO pdv_catalog_snapshot_product
                            (terminal_id, tenant_id, company_id, version, product_id, sku, barcode, name, unit, status)
                        VALUES ('terminal-1','tenant-1','company-1',?,?,'SKU-1','000001','?','UN','active')
                        """.replace("'?'", "?"))) {
                    insert.setLong(1, version);
                    insert.setString(2, productId);
                    insert.setString(3, name);
                    insert.executeUpdate();
                }
                if (price != null) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO pdv_catalog_snapshot_price
                                (terminal_id, tenant_id, company_id, version, product_id, currency, amount)
                            VALUES ('terminal-1','tenant-1','company-1',?,?,'BRL',?)
                            """)) {
                        insert.setLong(1, version);
                        insert.setString(2, productId);
                        insert.setString(3, price);
                        insert.executeUpdate();
                    }
                }
                try (PreparedStatement active = connection.prepareStatement("""
                        INSERT INTO pdv_catalog_snapshot_active
                            (terminal_id, tenant_id, company_id, version, activated_at)
                        VALUES ('terminal-1','tenant-1','company-1',?,?)
                        ON CONFLICT (terminal_id,tenant_id,company_id) DO UPDATE SET
                            version=excluded.version, activated_at=excluded.activated_at
                        """)) {
                    active.setLong(1, version);
                    active.setString(2, NOW.plusSeconds(version).toString());
                    active.executeUpdate();
                }
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private int scalarInt(String sql) {
        try (Connection connection = connections.openConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private String scalar(String sql) {
        try (Connection connection = connections.openConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            if (!result.next()) throw new AssertionError("No row returned");
            return result.getString(1);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Money money(String amount) {
        return new Money(new BigDecimal(amount), "BRL");
    }
}
