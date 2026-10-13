package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.domain.sale.Sale;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the locally confirmed sales history. */
public interface SaleRepository {

    /** Saves a new confirmed sale and its eligible synchronization event atomically. */
    Sale saveConfirmed(Sale sale);

    Optional<Sale> findById(UUID saleId);

    /** Returns this terminal's sales ordered by confirmation time, then UUID. */
    List<Sale> findByTerminalId(String terminalId);

    /** Changes only cancellation fields and appends the cancellation event atomically. */
    Sale cancel(UUID saleId, String reason, Instant cancelledAt);

    /** Declarations not yet linked to a completed sale; callers must not treat them as received automatically. */
    default List<ManualPaymentAttemptSummary> findManualPaymentAttemptsForReconciliation(String terminalId) {
        return List.of();
    }
}
