package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SalesMigrationTest {

    private static final String ID_1  = "00000000-0000-4000-8000-000000000001";
    private static final String OBX_1 = "00000000-0000-4000-8000-000000000011";
    private static final String AT    = "2026-10-12T10:00:00Z";

    @TempDir
    Path temporaryDirectory;

    // ─────────────────────────────────────────────────────────────────────────
    // Migration sequence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void v7AppliesToSchemaAfterV6WithoutErrors() throws Exception {
        var factory = migrationsUpTo("7");
        try (Connection conn = factory.openConnection()) {
            assertEquals(1, queryInt(conn,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success = 1"));
        }
    }

    @Test
    void v8AppliesToSchemaAfterV7WithoutErrors() throws Exception {
        var factory = migrationsUpTo("8");
        try (Connection conn = factory.openConnection()) {
            assertEquals(1, queryInt(conn,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success = 1"));
        }
    }

    @Test
    void fullMigrationSequenceV1ToV9AppliesCleanly() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertEquals(9, queryInt(conn,
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Schema presence
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void salesTablesExistAfterV7() throws Exception {
        var factory = migrationsUpTo("7");
        try (Connection conn = factory.openConnection()) {
            assertEquals(2, queryInt(conn,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'" +
                    " AND name IN ('pdv_sale', 'pdv_sale_item')"));
        }
    }

    @Test
    void outboxTableExistsAfterV8() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertEquals(1, queryInt(conn,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'" +
                    " AND name = 'pdv_sale_outbox'"));
        }
    }

    @Test
    void v9CreatesManualPaymentAttemptsAndClassifiesLegacySales() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            insertSale(conn, ID_1);
            assertEquals(1, queryInt(conn,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'pdv_manual_payment_attempt'"));
            assertEquals(1, queryInt(conn,
                    "SELECT COUNT(*) FROM pdv_sale WHERE payment_confirmation_origin = 'LEGACY_UNCLASSIFIED'"));
        }
    }

    @Test
    void v9RejectsNonManualAttemptOriginsAndUnsupportedPaymentMethods() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertThrows(SQLException.class, () -> conn.createStatement().executeUpdate(
                    "INSERT INTO pdv_manual_payment_attempt " +
                    "(confirmation_id,sale_id,terminal_id,tenant_id,company_id,branch_id," +
                    "prepared_by_operator_id,prepared_by_operator_name,currency,subtotal_amount," +
                    "discount_amount,total_amount,payment_method,origin,state,created_at) " +
                    "VALUES ('00000000-0000-4000-8000-000000000091'," +
                    "'00000000-0000-4000-8000-000000000092','t','tn','c','b','op','Operator'," +
                    "'BRL','1.00','0.00','1.00','OTHER','MANUAL','PREPARED','2026-10-12T00:00:00Z')"));
            assertThrows(SQLException.class, () -> conn.createStatement().executeUpdate(
                    "INSERT INTO pdv_manual_payment_attempt " +
                    "(confirmation_id,sale_id,terminal_id,tenant_id,company_id,branch_id," +
                    "prepared_by_operator_id,prepared_by_operator_name,currency,subtotal_amount," +
                    "discount_amount,total_amount,payment_method,origin,state,created_at) " +
                    "VALUES ('00000000-0000-4000-8000-000000000093'," +
                    "'00000000-0000-4000-8000-000000000094','t','tn','c','b','op','Operator'," +
                    "'BRL','1.00','0.00','1.00','CASH','PROVIDER_VERIFIED','PREPARED','2026-10-12T00:00:00Z')"));
        }
    }

    @Test
    void expectedIndexesExistAfterAllMigrations() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertEquals(3, queryInt(conn,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index'" +
                    " AND name IN ('pdv_sale_terminal_date', 'pdv_sale_status'," +
                    "              'pdv_sale_outbox_pending')"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRAGMA
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void foreignKeysAreEnabledOnEveryConnection() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertEquals(1, queryInt(conn, "PRAGMA foreign_keys"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // pdv_sale CHECK constraints
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void saleIdWithInvalidLengthIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertThrows(SQLException.class, () ->
                insertSaleWithOverride(conn, "too-short", "CONFIRMED", "BRL", "CASH"));
        }
    }

    @Test
    void invalidSaleStatusIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertThrows(SQLException.class, () ->
                insertSaleWithOverride(conn, ID_1, "DRAFT", "BRL", "CASH"));
        }
    }

    @Test
    void invalidPaymentMethodIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertThrows(SQLException.class, () ->
                insertSaleWithOverride(conn, ID_1, "CONFIRMED", "BRL", "CRYPTO"));
        }
    }

    @Test
    void invalidCurrencyLengthIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertThrows(SQLException.class, () ->
                insertSaleWithOverride(conn, ID_1, "CONFIRMED", "BR", "CASH"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // pdv_sale_item constraints
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void zeroLineNumberIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            insertSale(conn, ID_1);
            assertThrows(SQLException.class, () -> insertItem(conn, ID_1, 0));
        }
    }

    @Test
    void saleItemWithoutExistingSaleIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertThrows(SQLException.class, () -> insertItem(conn, ID_1, 1));
        }
    }

    @Test
    void deletingSaleWithItemsIsRestricted() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            insertSale(conn, ID_1);
            insertItem(conn, ID_1, 1);
            assertThrows(SQLException.class, () -> deleteSale(conn, ID_1));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // pdv_sale_outbox constraints
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void outboxWithoutExistingSaleIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertThrows(SQLException.class, () -> insertOutbox(conn, OBX_1, ID_1, "SALE_CONFIRMED"));
        }
    }

    @Test
    void invalidOutboxEventTypeIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            insertSale(conn, ID_1);
            assertThrows(SQLException.class, () -> insertOutbox(conn, OBX_1, ID_1, "SALE_MODIFIED"));
        }
    }

    @Test
    void invalidOutboxStatusIsRejected() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            insertSale(conn, ID_1);
            assertThrows(SQLException.class, () ->
                insertOutboxWithStatus(conn, OBX_1, ID_1, "QUEUED"));
        }
    }

    @Test
    void deletingSaleWithOutboxIsRestricted() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            insertSale(conn, ID_1);
            insertOutbox(conn, OBX_1, ID_1, "SALE_CONFIRMED");
            assertThrows(SQLException.class, () -> deleteSale(conn, ID_1));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Backward compatibility
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void catalogTablesFromV6AreUnaffectedByV7AndV8() throws Exception {
        var factory = allMigrations();
        try (Connection conn = factory.openConnection()) {
            assertEquals(3, queryInt(conn,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table'" +
                    " AND name IN ('pdv_catalog_product', 'pdv_catalog_sale_price'," +
                    "              'pdv_catalog_package')"));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — factory
    // ─────────────────────────────────────────────────────────────────────────

    private SqliteConnectionFactory allMigrations() {
        var directory = new LocalDataDirectory(
                () -> new WindowsPlatformPaths(key -> temporaryDirectory.toString()));
        return new LocalDatabaseInitializer(directory).initialize();
    }

    private SqliteConnectionFactory migrationsUpTo(String version) {
        var directory = new LocalDataDirectory(
                () -> new WindowsPlatformPaths(key -> temporaryDirectory.toString()));
        directory.prepareOperationalDirectories();
        var factory = new SqliteConnectionFactory(directory.databasePath());
        Flyway.configure()
                .dataSource(factory.jdbcUrl(), null, null)
                .locations("classpath:db/migration")
                .target(version)
                .cleanDisabled(true)
                .load()
                .migrate();
        return factory;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — DML
    // ─────────────────────────────────────────────────────────────────────────

    private void insertSale(Connection conn, String saleId) throws SQLException {
        insertSaleWithOverride(conn, saleId, "CONFIRMED", "BRL", "CASH");
    }

    private void insertSaleWithOverride(Connection conn, String saleId,
            String status, String currency, String paymentMethod) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pdv_sale (sale_id, terminal_id, tenant_id, company_id, branch_id," +
                "  operator_id, operator_name, status, currency, subtotal_amount, total_amount," +
                "  payment_method, payment_amount, confirmed_at, created_at)" +
                " VALUES (?, 't1', 'tn1', 'c1', 'b1', 'op1', 'Caixa'," +
                "  ?, ?, '10.00', '10.00', ?, '10.00', ?, ?)")) {
            ps.setString(1, saleId);
            ps.setString(2, status);
            ps.setString(3, currency);
            ps.setString(4, paymentMethod);
            ps.setString(5, AT);
            ps.setString(6, AT);
            ps.executeUpdate();
        }
    }

    private void insertItem(Connection conn, String saleId, int lineNumber) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pdv_sale_item (sale_id, line_number, product_id, product_name," +
                "  product_unit, quantity, unit_price_amount, total_amount)" +
                " VALUES (?, ?, 'prod-1', 'Produto Teste', 'UN', '1', '10.00', '10.00')")) {
            ps.setString(1, saleId);
            ps.setInt(2, lineNumber);
            ps.executeUpdate();
        }
    }

    private void insertOutbox(Connection conn, String outboxId,
            String saleId, String eventType) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pdv_sale_outbox (outbox_id, sale_id, event_type, created_at)" +
                " VALUES (?, ?, ?, ?)")) {
            ps.setString(1, outboxId);
            ps.setString(2, saleId);
            ps.setString(3, eventType);
            ps.setString(4, AT);
            ps.executeUpdate();
        }
    }

    private void insertOutboxWithStatus(Connection conn, String outboxId,
            String saleId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pdv_sale_outbox (outbox_id, sale_id, event_type, status, created_at)" +
                " VALUES (?, ?, 'SALE_CONFIRMED', ?, ?)")) {
            ps.setString(1, outboxId);
            ps.setString(2, saleId);
            ps.setString(3, status);
            ps.setString(4, AT);
            ps.executeUpdate();
        }
    }

    private void deleteSale(Connection conn, String saleId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM pdv_sale WHERE sale_id = ?")) {
            ps.setString(1, saleId);
            ps.executeUpdate();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers — query
    // ─────────────────────────────────────────────────────────────────────────

    private int queryInt(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
