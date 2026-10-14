package br.com.lucraone.pdv.infrastructure.persistence;

import br.com.lucraone.pdv.application.sale.SaleRepository;
import br.com.lucraone.pdv.application.sale.ManualPaymentAttemptSummary;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
import br.com.lucraone.pdv.domain.sale.SaleItem;
import br.com.lucraone.pdv.domain.sale.SalePayment;
import br.com.lucraone.pdv.domain.sale.SaleStatus;
import br.com.lucraone.pdv.domain.sale.PaymentConfirmationOrigin;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** JDBC adapter for V7 sales and V8 outbox, coordinated by one SQLite transaction. */
public final class JdbcSaleRepository implements SaleRepository {

    private static final String SELECT_SALE = """
            SELECT sale_id, terminal_id, tenant_id, company_id, branch_id,
                   operator_id, operator_name, status, currency, subtotal_amount,
                   discount_amount, total_amount, payment_method, payment_amount,
                   change_amount, confirmed_at, cancelled_at, cancellation_reason,
                   payment_confirmation_origin,
                   fiscal_document_type, fiscal_number, created_at
            FROM pdv_sale WHERE sale_id = ?
            """;

    private final JdbcTransactionManager transactions;
    private final JdbcSaleOutboxRepository outbox;

    public JdbcSaleRepository(JdbcTransactionManager transactions) {
        this(transactions, new JdbcSaleOutboxRepository());
    }

    JdbcSaleRepository(JdbcTransactionManager transactions, JdbcSaleOutboxRepository outbox) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    }

    @Override
    public Sale saveConfirmed(Sale sale) {
        Objects.requireNonNull(sale, "sale must not be null");
        if (sale.status() != SaleStatus.CONFIRMED) {
            throw new IllegalArgumentException("only a new CONFIRMED sale can be saved");
        }
        if (sale.payment().confirmationOrigin() == PaymentConfirmationOrigin.PROVIDER_VERIFIED) {
            throw new IllegalArgumentException("Provider-verified payments are unavailable in the manual MVP repository.");
        }

        UUID confirmationId = sale.payment().confirmationOrigin() == PaymentConfirmationOrigin.MANUAL
                ? UUID.nameUUIDFromBytes(("lucraone-manual-payment-v1:" + sale.saleId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)) : null;
        if (confirmationId != null) {
            persistDeclaration(sale, confirmationId);
        }
        try {
        return transactions.inTransaction((connection, transaction) -> {
            Optional<Sale> existing = findById(connection, sale.saleId());
            if (existing.isPresent()) {
                if (existing.orElseThrow().equals(sale)) return existing.orElseThrow();
                throw new LocalDatabaseException("Sale UUID was already used for a different sale.");
            }
            insertHeader(connection, sale);
            insertItems(connection, sale);
            outbox.enqueue(connection, sale.saleId(),
                    JdbcSaleOutboxRepository.EventType.SALE_CONFIRMED, sale.confirmedAt());
            if (confirmationId != null) completeDeclaration(connection, confirmationId, sale);
            return sale;
        });
        } catch (RuntimeException failure) {
            if (confirmationId != null) markReconciliationRequired(confirmationId, failure);
            throw failure;
        }
    }

    private void markReconciliationRequired(UUID confirmationId, RuntimeException originalFailure) {
        try {
            transactions.inTransaction((connection, transaction) -> {
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE pdv_manual_payment_attempt SET state='RECONCILIATION_REQUIRED'
                        WHERE confirmation_id=? AND state='DECLARED'
                        """)) {
                    update.setString(1, confirmationId.toString());
                    update.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException auditFailure) {
            // The committed declaration remains queryable even if this status annotation fails.
            originalFailure.addSuppressed(auditFailure);
        }
    }

    /** Commits the operator declaration first. A crash before sale/outbox leaves a recoverable DECLARED row. */
    private void persistDeclaration(Sale sale, UUID confirmationId) {
        transactions.inTransaction((connection, transaction) -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT state, sale_id, total_amount, payment_method, payment_amount, change_amount FROM pdv_manual_payment_attempt WHERE confirmation_id = ?")) {
                query.setString(1, confirmationId.toString());
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        if ("RECONCILIATION_REQUIRED".equals(result.getString("state"))) {
                            throw new IllegalStateException("Manual payment attempt requires explicit reconciliation.");
                        }
                        if (!sale.saleId().toString().equals(result.getString("sale_id"))
                                || !sale.totalAmount().toPlainString().equals(result.getString("total_amount"))
                                || !sale.payment().method().name().equals(result.getString("payment_method"))
                                || !sale.payment().paymentAmount().toPlainString().equals(result.getString("payment_amount"))
                                || !sale.payment().changeAmount().toPlainString().equals(result.getString("change_amount")))
                            throw new IllegalStateException("Idempotency key was reused with different sale data.");
                        return null;
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO pdv_manual_payment_attempt (
                        confirmation_id,sale_id,terminal_id,tenant_id,company_id,branch_id,
                        prepared_by_operator_id,prepared_by_operator_name,currency,subtotal_amount,
                        discount_amount,total_amount,payment_method,origin,state,payment_amount,
                        change_amount,declaration_type,declared_by_operator_id,declared_by_operator_name,
                        declared_at,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'MANUAL','DECLARED',?,?,?,?,?,?,?)
                    """)) {
                int i=1;
                insert.setString(i++,confirmationId.toString()); insert.setString(i++,sale.saleId().toString());
                insert.setString(i++,sale.terminalId()); insert.setString(i++,sale.tenantId());
                insert.setString(i++,sale.companyId()); insert.setString(i++,sale.branchId());
                insert.setString(i++,sale.operatorId()); insert.setString(i++,sale.operatorName());
                insert.setString(i++,sale.currency()); insert.setString(i++,sale.subtotalAmount().toPlainString());
                insert.setString(i++,sale.discountAmount().toPlainString()); insert.setString(i++,sale.totalAmount().toPlainString());
                insert.setString(i++,sale.payment().method().name());
                insert.setString(i++,sale.payment().paymentAmount().toPlainString());
                insert.setString(i++,sale.payment().changeAmount().toPlainString());
                insert.setString(i++,declarationFor(sale.payment().method()));
                insert.setString(i++,sale.operatorId()); insert.setString(i++,sale.operatorName());
                insert.setString(i++,sale.confirmedAt().toString()); insert.setString(i,sale.createdAt().toString());
                insert.executeUpdate();
            }
            insertAttemptItems(connection, confirmationId, sale.items());
            return null;
        });
    }

    private static String declarationFor(PaymentMethod method) {
        return switch (method) {
            case CASH -> "CASH_RECEIVED";
            case CARD_DEBIT, CARD_CREDIT -> "EXTERNAL_CARD_APPROVED";
            case PIX -> "PIX_OFFICIAL_CHANNEL_CHECKED";
            case MEAL_VOUCHER, FOOD_VOUCHER -> "EXTERNAL_VOUCHER_APPROVED";
            case OTHER -> throw new IllegalArgumentException("OTHER is disabled for manual payments.");
        };
    }

    private static void insertAttemptItems(Connection connection, UUID id, List<SaleItem> items) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO pdv_manual_payment_attempt_item (
                    confirmation_id,line_number,product_id,product_name,product_sku,product_unit,
                    quantity,unit_price_amount,discount_amount,total_amount,package_id,package_name,package_factor)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (SaleItem item : items) {
                int i=1; insert.setString(i++,id.toString()); insert.setInt(i++,item.lineNumber());
                insert.setString(i++,item.productId()); insert.setString(i++,item.productName());
                setOptionalString(insert,i++,item.productSku()); insert.setString(i++,item.productUnit());
                insert.setString(i++,item.quantity().toPlainString()); insert.setString(i++,item.unitPrice().toPlainString());
                insert.setString(i++,item.discountAmount().toPlainString()); insert.setString(i++,item.totalAmount().toPlainString());
                setOptionalString(insert,i++,item.packageId()); setOptionalString(insert,i++,item.packageName());
                if (item.packageFactor().isPresent()) insert.setString(i,item.packageFactor().orElseThrow().toPlainString());
                else insert.setNull(i,Types.VARCHAR);
                insert.executeUpdate();
            }
        }
    }

    private static void completeDeclaration(Connection connection, UUID id, Sale sale) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE pdv_manual_payment_attempt SET state='COMPLETED', linked_sale_id=?
                WHERE confirmation_id=? AND sale_id=? AND state='DECLARED'
                """)) {
            update.setString(1,sale.saleId().toString()); update.setString(2,id.toString());
            update.setString(3,sale.saleId().toString());
            if (update.executeUpdate()!=1) throw new IllegalStateException("Manual payment declaration is not completable.");
        }
    }

    @Override
    public Optional<Sale> findById(UUID saleId) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        return transactions.inTransaction((connection, transaction) -> findById(connection, saleId));
    }

    @Override
    public List<Sale> findByTerminalId(String terminalId) {
        Objects.requireNonNull(terminalId, "terminalId must not be null");
        if (terminalId.isBlank()) {
            throw new IllegalArgumentException("terminalId must not be blank");
        }
        return transactions.inTransaction((connection, transaction) -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT sale_id FROM pdv_sale
                    WHERE terminal_id = ?
                    ORDER BY confirmed_at, sale_id
                    """)) {
                select.setString(1, terminalId);
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        ids.add(parseUuid(result.getString("sale_id"), "sale_id"));
                    }
                }
            }
            List<Sale> sales = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                sales.add(findById(connection, id).orElseThrow());
            }
            return List.copyOf(sales);
        });
    }

    @Override
    public List<ManualPaymentAttemptSummary> findManualPaymentAttemptsForReconciliation(String terminalId) {
        Objects.requireNonNull(terminalId, "terminalId must not be null");
        return transactions.inTransaction((connection, transaction) -> {
            List<ManualPaymentAttemptSummary> attempts = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT confirmation_id,sale_id,payment_method,payment_amount,currency,state,created_at
                    FROM pdv_manual_payment_attempt
                    WHERE terminal_id=? AND state IN ('DECLARED','RECONCILIATION_REQUIRED')
                    ORDER BY created_at,confirmation_id
                    """)) {
                select.setString(1, terminalId);
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        String storedAmount = result.getString("payment_amount");
                        if (storedAmount == null) continue;
                        attempts.add(new ManualPaymentAttemptSummary(
                                parseUuid(required(result,"confirmation_id"),"confirmation_id"),
                                parseUuid(required(result,"sale_id"),"sale_id"),
                                PaymentMethod.valueOf(required(result,"payment_method")),
                                readMoney(result,"payment_amount",required(result,"currency")),
                                required(result,"state"),
                                parseInstant(required(result,"created_at"),"created_at")));
                    }
                }
            }
            return List.copyOf(attempts);
        });
    }

    @Override
    public Sale cancel(UUID saleId, String reason, Instant cancelledAt) {
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");

        return transactions.inTransaction((connection, transaction) -> {
            Sale current = findById(connection, saleId)
                    .orElseThrow(() -> new NoSuchElementException("Sale not found: " + saleId));
            Sale cancelled = current.cancel(reason, cancelledAt);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE pdv_sale
                    SET status = 'CANCELLED', cancelled_at = ?, cancellation_reason = ?
                    WHERE sale_id = ? AND status = 'CONFIRMED'
                    """)) {
                update.setString(1, cancelledAt.toString());
                update.setString(2, cancelled.cancellationReason().orElseThrow());
                update.setString(3, saleId.toString());
                if (update.executeUpdate() != 1) {
                    throw new IllegalStateException("sale was concurrently changed or already cancelled");
                }
            }
            outbox.enqueue(connection, saleId,
                    JdbcSaleOutboxRepository.EventType.SALE_CANCELLED, cancelledAt);
            return cancelled;
        });
    }

    private Optional<Sale> findById(Connection connection, UUID saleId) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(SELECT_SALE)) {
            select.setString(1, saleId.toString());
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                Sale sale = mapSale(connection, result);
                if (result.next()) {
                    throw new LocalDatabaseException("Duplicate sale_id returned by SQLite.");
                }
                return Optional.of(sale);
            }
        }
    }

    private Sale mapSale(Connection connection, ResultSet row) throws SQLException {
        try {
            UUID saleId = parseUuid(row.getString("sale_id"), "sale_id");
            String currency = required(row, "currency");
            List<SaleItem> items = readItems(connection, saleId, currency);
            Optional<String> cancelledAtText = optionalString(row, "cancelled_at");
            Optional<String> reason = optionalString(row, "cancellation_reason");
            Optional<String> fiscalType = optionalString(row, "fiscal_document_type");
            Optional<String> fiscalNumber = optionalString(row, "fiscal_number");

            SalePayment payment = new SalePayment(
                    PaymentMethod.valueOf(required(row, "payment_method")),
                    readMoney(row, "payment_amount", currency),
                    readMoney(row, "change_amount", currency),
                    PaymentConfirmationOrigin.valueOf(required(row, "payment_confirmation_origin")));

            return new Sale(
                    saleId,
                    required(row, "terminal_id"),
                    required(row, "tenant_id"),
                    required(row, "company_id"),
                    required(row, "branch_id"),
                    required(row, "operator_id"),
                    required(row, "operator_name"),
                    SaleStatus.valueOf(required(row, "status")),
                    currency,
                    readMoney(row, "subtotal_amount", currency),
                    readMoney(row, "discount_amount", currency),
                    readMoney(row, "total_amount", currency),
                    payment,
                    items,
                    parseInstant(required(row, "confirmed_at"), "confirmed_at"),
                    cancelledAtText.map(value -> parseInstant(value, "cancelled_at")),
                    reason,
                    fiscalType,
                    fiscalNumber,
                    parseInstant(required(row, "created_at"), "created_at"));
        } catch (IllegalArgumentException exception) {
            throw new LocalDatabaseException("Stored sale data is invalid or inconsistent.", exception);
        }
    }

    private List<SaleItem> readItems(Connection connection, UUID saleId, String currency) throws SQLException {
        List<SaleItem> items = new ArrayList<>();
        try (PreparedStatement select = connection.prepareStatement("""
                SELECT line_number, product_id, product_name, product_sku, product_unit,
                       quantity, unit_price_amount, discount_amount, total_amount,
                       package_id, package_name, package_factor
                FROM pdv_sale_item WHERE sale_id = ? ORDER BY line_number
                """)) {
            select.setString(1, saleId.toString());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    Optional<String> packageId = optionalString(result, "package_id");
                    Optional<String> packageName = optionalString(result, "package_name");
                    Optional<String> packageFactor = optionalString(result, "package_factor");
                    items.add(new SaleItem(
                            result.getInt("line_number"),
                            required(result, "product_id"),
                            required(result, "product_name"),
                            optionalString(result, "product_sku"),
                            required(result, "product_unit"),
                            new BigDecimal(required(result, "quantity")),
                            readMoney(result, "unit_price_amount", currency),
                            readMoney(result, "discount_amount", currency),
                            readMoney(result, "total_amount", currency),
                            packageId,
                            packageName,
                            packageFactor.map(BigDecimal::new)));
                }
            }
        }
        return List.copyOf(items);
    }

    private void insertHeader(Connection connection, Sale sale) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO pdv_sale (
                    sale_id, terminal_id, tenant_id, company_id, branch_id,
                    operator_id, operator_name, status, currency, subtotal_amount,
                    discount_amount, total_amount, payment_method, payment_amount,
                    change_amount, confirmed_at, cancelled_at, cancellation_reason,
                    fiscal_document_type, fiscal_number, created_at,
                    payment_confirmation_origin, payment_confirmation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int i = 1;
            insert.setString(i++, sale.saleId().toString());
            insert.setString(i++, sale.terminalId());
            insert.setString(i++, sale.tenantId());
            insert.setString(i++, sale.companyId());
            insert.setString(i++, sale.branchId());
            insert.setString(i++, sale.operatorId());
            insert.setString(i++, sale.operatorName());
            insert.setString(i++, sale.status().name());
            insert.setString(i++, sale.currency());
            insert.setString(i++, sale.subtotalAmount().toPlainString());
            insert.setString(i++, sale.discountAmount().toPlainString());
            insert.setString(i++, sale.totalAmount().toPlainString());
            insert.setString(i++, sale.payment().method().name());
            insert.setString(i++, sale.payment().paymentAmount().toPlainString());
            insert.setString(i++, sale.payment().changeAmount().toPlainString());
            insert.setString(i++, sale.confirmedAt().toString());
            setOptionalString(insert, i++, Optional.empty());
            setOptionalString(insert, i++, Optional.empty());
            setOptionalString(insert, i++, sale.fiscalDocumentType());
            setOptionalString(insert, i++, sale.fiscalNumber());
            insert.setString(i++, sale.createdAt().toString());
            insert.setString(i++, sale.payment().confirmationOrigin().name());
            if (sale.payment().confirmationOrigin() == PaymentConfirmationOrigin.MANUAL) {
                insert.setString(i, UUID.nameUUIDFromBytes(("lucraone-manual-payment-v1:" + sale.saleId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            } else {
                insert.setNull(i, Types.VARCHAR);
            }
            insert.executeUpdate();
        }
    }

    private void insertItems(Connection connection, Sale sale) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO pdv_sale_item (
                    sale_id, line_number, product_id, product_name, product_sku,
                    product_unit, quantity, unit_price_amount, discount_amount,
                    total_amount, package_id, package_name, package_factor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (SaleItem item : sale.items()) {
                int i = 1;
                insert.setString(i++, sale.saleId().toString());
                insert.setInt(i++, item.lineNumber());
                insert.setString(i++, item.productId());
                insert.setString(i++, item.productName());
                setOptionalString(insert, i++, item.productSku());
                insert.setString(i++, item.productUnit());
                insert.setString(i++, item.quantity().toPlainString());
                insert.setString(i++, item.unitPrice().toPlainString());
                insert.setString(i++, item.discountAmount().toPlainString());
                insert.setString(i++, item.totalAmount().toPlainString());
                setOptionalString(insert, i++, item.packageId());
                setOptionalString(insert, i++, item.packageName());
                if (item.packageFactor().isPresent()) {
                    insert.setString(i, item.packageFactor().orElseThrow().toPlainString());
                } else {
                    insert.setNull(i, Types.VARCHAR);
                }
                insert.executeUpdate();
            }
        }
    }

    private static Money readMoney(ResultSet result, String column, String currency) throws SQLException {
        String stored = required(result, column);
        Money money = new Money(new BigDecimal(stored), currency);
        if (!money.toPlainString().equals(stored)) {
            throw new IllegalArgumentException("non-canonical monetary value in " + column);
        }
        return money;
    }

    private static String required(ResultSet result, String column) throws SQLException {
        String value = result.getString(column);
        if (value == null) {
            throw new IllegalArgumentException("required column is NULL: " + column);
        }
        return value;
    }

    private static Optional<String> optionalString(ResultSet result, String column) throws SQLException {
        return Optional.ofNullable(result.getString(column));
    }

    private static void setOptionalString(PreparedStatement statement, int index, Optional<String> value)
            throws SQLException {
        if (value.isPresent()) {
            statement.setString(index, value.orElseThrow());
        } else {
            statement.setNull(index, Types.VARCHAR);
        }
    }

    private static UUID parseUuid(String value, String column) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("non-canonical UUID in " + column);
        }
        return uuid;
    }

    private static Instant parseInstant(String value, String column) {
        Instant instant = Instant.parse(value);
        if (!instant.toString().equals(value)) {
            throw new IllegalArgumentException("non-canonical UTC timestamp in " + column);
        }
        return instant;
    }
}
