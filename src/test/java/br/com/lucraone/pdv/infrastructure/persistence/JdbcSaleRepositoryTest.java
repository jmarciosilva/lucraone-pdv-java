package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
import br.com.lucraone.pdv.domain.sale.SaleItem;
import br.com.lucraone.pdv.domain.sale.SalePayment;
import br.com.lucraone.pdv.domain.sale.SaleStatus;
import br.com.lucraone.pdv.domain.sale.PaymentConfirmationOrigin;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcSaleRepositoryTest {

    private static final String BRL = "BRL";
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-12T10:15:30.123456Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-10-12T10:20:00Z");
    private static final UUID SALE_ID = UUID.fromString("00000000-0000-4000-8000-000000000101");

    @TempDir
    Path temporaryDirectory;

    private LocalDataDirectory directory;
    private SqliteConnectionFactory connections;
    private JdbcSaleRepository repository;

    @BeforeEach
    void setUp() {
        directory = new LocalDataDirectory(() -> new WindowsPlatformPaths(key -> temporaryDirectory.toString()));
        connections = new LocalDatabaseInitializer(directory).initialize();
        repository = new JdbcSaleRepository(new JdbcTransactionManager(connections));
    }

    @Test
    void savesAndReconstructsSaleWithOneItemAndExactMoney() {
        Sale sale = sale(SALE_ID, List.of(item(1, "prod-1", "Café", "1", "12.35")));

        assertEquals(sale, repository.saveConfirmed(sale));
        assertEquals(Optional.of(sale), repository.findById(SALE_ID));
        assertEquals("12.35", repository.findById(SALE_ID).orElseThrow().totalAmount().toPlainString());
    }

    @Test
    void savesSaleWithMultipleItemsAndFindsByTerminal() {
        Sale sale = sale(SALE_ID, List.of(
                item(1, "prod-1", "Produto A", "2", "10.00"),
                item(2, "prod-2", "Produto B", "1.25", "8.40")));

        repository.saveConfirmed(sale);

        assertEquals(sale, repository.findByTerminalId("terminal-1").getFirst());
        assertEquals(2, count("pdv_sale_item"));
    }

    @Test
    void preservesProductAndPackageSnapshots() {
        SaleItem snapshot = SaleItem.of(1, "catalog-id-7", "Nome histórico", Optional.of("SKU-OLD"),
                "CX", new BigDecimal("1"), money("24.90"), money("1.00"),
                Optional.of("pack-24"), Optional.of("Caixa com 24"), Optional.of(new BigDecimal("24")));
        Sale stored = repository.saveConfirmed(sale(SALE_ID, List.of(snapshot)));

        SaleItem reconstructed = repository.findById(SALE_ID).orElseThrow().items().getFirst();
        assertEquals(snapshot, reconstructed);
        assertEquals(stored.items(), List.of(reconstructed));
    }

    @Test
    void confirmedSaleAndOutboxEventCommitTogether() throws SQLException {
        repository.saveConfirmed(sale(SALE_ID, List.of(item(1, "p1", "Produto", "1", "10.00"))));

        assertEquals(1, count("pdv_sale"));
        assertEquals(1, count("pdv_sale_item"));
        assertEquals(1, countEvent("SALE_CONFIRMED"));
    }

    @Test
    void itemInsertFailureRollsBackHeaderItemsAndOutbox() throws SQLException {
        execute("""
                CREATE TRIGGER reject_sale_item BEFORE INSERT ON pdv_sale_item
                BEGIN SELECT RAISE(ABORT, 'injected item failure'); END
                """);

        assertThrows(LocalDatabaseException.class,
                () -> repository.saveConfirmed(sale(SALE_ID, List.of(item(1, "p1", "Produto", "1", "10.00")))));

        assertEquals(0, count("pdv_sale"));
        assertEquals(0, count("pdv_sale_item"));
        assertEquals(0, count("pdv_sale_outbox"));
    }

    @Test
    void outboxInsertFailureRollsBackHeaderAndItems() throws SQLException {
        execute("""
                CREATE TRIGGER reject_sale_outbox BEFORE INSERT ON pdv_sale_outbox
                BEGIN SELECT RAISE(ABORT, 'injected outbox failure'); END
                """);

        assertThrows(LocalDatabaseException.class,
                () -> repository.saveConfirmed(sale(SALE_ID, List.of(item(1, "p1", "Produto", "1", "10.00")))));

        assertEquals(0, count("pdv_sale"));
        assertEquals(0, count("pdv_sale_item"));
        assertEquals(0, count("pdv_sale_outbox"));
    }

    @Test
    void duplicateSaleUuidIsRejectedWithoutChangingOriginal() {
        Sale original = sale(SALE_ID, List.of(item(1, "p1", "Original", "1", "10.00")));
        repository.saveConfirmed(original);
        Sale conflicting = sale(SALE_ID, List.of(item(1, "p2", "Substituto", "1", "20.00")));

        assertThrows(LocalDatabaseException.class, () -> repository.saveConfirmed(conflicting));
        assertEquals(Optional.of(original), repository.findById(SALE_ID));
        assertEquals(1, count("pdv_sale_outbox"));
    }

    @Test
    void providerVerifiedOriginCannotBePersistedThroughManualMvpRepository() {
        List<SaleItem> items = List.of(item(1, "p1", "Produto", "1", "10.00"));
        Sale providerClaim = Sale.confirm(SALE_ID, "terminal-1", "tenant-1", "company-1", "branch-1",
                "operator-1", "Operador", BRL, items, money("0.00"),
                new SalePayment(PaymentMethod.CASH, money("10.00"), money("0.00"),
                        PaymentConfirmationOrigin.PROVIDER_VERIFIED), CONFIRMED_AT);

        assertThrows(IllegalArgumentException.class, () -> repository.saveConfirmed(providerClaim));
        assertEquals(0, count("pdv_sale"));
        assertEquals(0, count("pdv_manual_payment_attempt"));
    }

    @Test
    void inconsistentStoredLineIsRejectedOnRead() throws SQLException {
        repository.saveConfirmed(sale(SALE_ID, List.of(item(1, "p1", "Produto", "1", "10.00"))));
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE pdv_sale_item SET total_amount = '9.00' WHERE sale_id = '" + SALE_ID + "'");
        }

        assertThrows(LocalDatabaseException.class, () -> repository.findById(SALE_ID));
    }

    @Test
    void cancellationPreservesSaleHistoryAndAddsCancellationEvent() throws SQLException {
        Sale original = sale(SALE_ID, List.of(item(1, "p1", "Produto histórico", "2", "5.00")));
        repository.saveConfirmed(original);

        Sale cancelled = repository.cancel(SALE_ID, "Erro de lançamento", CANCELLED_AT);

        assertEquals(SaleStatus.CANCELLED, cancelled.status());
        assertEquals(original.items(), cancelled.items());
        assertEquals(original.totalAmount(), cancelled.totalAmount());
        assertEquals("Produto histórico", repository.findById(SALE_ID).orElseThrow().items().getFirst().productName());
        assertEquals(1, countEvent("SALE_CONFIRMED"));
        assertEquals(1, countEvent("SALE_CANCELLED"));
        assertEquals("CANCELLED", scalar("SELECT status FROM pdv_sale WHERE sale_id = '" + SALE_ID + "'"));
    }

    @Test
    void repeatedCancellationIsRejectedAndDoesNotDuplicateEvents() {
        repository.saveConfirmed(sale(SALE_ID, List.of(item(1, "p1", "Produto", "1", "10.00"))));
        repository.cancel(SALE_ID, "Motivo", CANCELLED_AT);

        assertThrows(IllegalStateException.class,
                () -> repository.cancel(SALE_ID, "Outro motivo", CANCELLED_AT.plusSeconds(1)));
        assertEquals(2, count("pdv_sale_outbox"));
    }

    @Test
    void reopeningDatabasePreservesSaleAndBothEvents() {
        Sale original = sale(SALE_ID, List.of(item(1, "p1", "Produto", "1.5", "4.20")));
        repository.saveConfirmed(original);
        repository.cancel(SALE_ID, "Motivo", CANCELLED_AT);

        SqliteConnectionFactory reopenedConnections = new LocalDatabaseInitializer(directory).initialize();
        JdbcSaleRepository reopened = new JdbcSaleRepository(new JdbcTransactionManager(reopenedConnections));

        assertEquals(SaleStatus.CANCELLED, reopened.findById(SALE_ID).orElseThrow().status());
        assertEquals(2, count(reopenedConnections, "pdv_sale_outbox"));
    }

    @Test
    void sqliteForeignKeysRejectOrphanItemAndOutboxRows() throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            assertEquals(1, pragmaInt(statement, "PRAGMA foreign_keys"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO pdv_sale_item (sale_id, line_number, product_id, product_name,
                        product_unit, quantity, unit_price_amount, total_amount)
                    VALUES ('00000000-0000-4000-8000-000000000999', 1, 'p', 'P', 'UN', '1.0000', '1.00', '1.00')
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO pdv_sale_outbox (outbox_id, sale_id, event_type, created_at)
                    VALUES ('00000000-0000-4000-8000-000000000998',
                        '00000000-0000-4000-8000-000000000999', 'SALE_CONFIRMED', '2026-10-12T10:00:00Z')
                    """));
        }
    }

    @Test
    void repositoryPersistsOfflineWithoutHttpGateway() {
        Sale sale = sale(SALE_ID, List.of(item(1, "p1", "Offline", "1", "2.00")));
        repository.saveConfirmed(sale);
        assertTrue(repository.findById(SALE_ID).isPresent());
    }

    @Test
    void noConfirmedSaleCanBeCommittedWithoutItsEvent() {
        Sale sale = sale(SALE_ID, List.of(item(1, "p1", "Produto", "1", "10.00")));
        repository.saveConfirmed(sale);

        assertEquals(1, count("pdv_sale"));
        assertEquals(1, countEvent("SALE_CONFIRMED"));
    }

    @Test
    void concurrentDuplicateUuidHasOneWinnerAndOneEvent() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> concurrentSave(start, "First"));
            Future<Boolean> second = executor.submit(() -> concurrentSave(start, "Second"));
            start.countDown();
            assertNotEquals(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("pdv_sale"));
        assertEquals(1, count("pdv_sale_outbox"));
    }

    private boolean concurrentSave(CountDownLatch start, String name) throws Exception {
        start.await();
        try {
            repository.saveConfirmed(sale(SALE_ID, List.of(item(1, "p1", name, "1", "10.00"))));
            return true;
        } catch (LocalDatabaseException expectedDuplicate) {
            return false;
        }
    }

    private Sale sale(UUID id, List<SaleItem> items) {
        Money subtotal = items.stream().map(SaleItem::totalAmount).reduce(Money.zero(BRL), Money::add);
        return Sale.confirm(id, "terminal-1", "tenant-1", "company-1", "branch-1",
                "operator-1", "Operador", BRL, items, money("0.00"),
                new SalePayment(PaymentMethod.CASH, subtotal, money("0.00")), CONFIRMED_AT);
    }

    private SaleItem item(int lineNumber, String id, String name, String quantity, String price) {
        return SaleItem.of(lineNumber, id, name, Optional.empty(), "UN", new BigDecimal(quantity),
                money(price), money("0.00"), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private Money money(String amount) {
        return new Money(new BigDecimal(amount), BRL);
    }

    private int count(String table) {
        return count(connections, table);
    }

    private int count(SqliteConnectionFactory factory, String table) {
        return scalarInt(factory, "SELECT COUNT(*) FROM " + table);
    }

    private int countEvent(String eventType) {
        return scalarInt(connections, "SELECT COUNT(*) FROM pdv_sale_outbox WHERE event_type = '" + eventType + "'");
    }

    private int scalarInt(SqliteConnectionFactory factory, String sql) {
        try (Connection connection = factory.openConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connections.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int pragmaInt(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
