package br.com.lucraone.pdv.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Writes sales events using the caller's connection. It deliberately owns no transaction manager:
 * enqueue must run inside the sale transaction so a sale cannot be committed without its event.
 */
public final class JdbcSaleOutboxRepository {

    enum EventType {
        SALE_CONFIRMED,
        SALE_CANCELLED
    }

    void enqueue(Connection connection, UUID saleId, EventType eventType, Instant createdAt)
            throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(saleId, "saleId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        UUID outboxId = stableEventId(saleId, eventType);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO pdv_sale_outbox (outbox_id, sale_id, event_type, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            insert.setString(1, outboxId.toString());
            insert.setString(2, saleId.toString());
            insert.setString(3, eventType.name());
            insert.setString(4, createdAt.toString());
            insert.executeUpdate();
        }
    }

    private static UUID stableEventId(UUID saleId, EventType eventType) {
        String identity = "lucraone-pdv:sale-outbox:v1:" + eventType.name() + ":" + saleId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }
}
