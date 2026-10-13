package br.com.lucraone.pdv.domain.sale;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Confirmed sale aggregate root. A {@code Sale} is written once after local payment confirmation;
 * no draft or in-progress state is represented here.
 *
 * <p>Use {@link #confirm} to create a confirmed sale — it computes {@code subtotalAmount} and
 * {@code totalAmount} from the provided items and sale-level discount. Use {@link #cancel} to
 * transition to {@link SaleStatus#CANCELLED}; that method returns a new immutable instance.
 *
 * <p>Fiscal attributes ({@code fiscalDocumentType}, {@code fiscalNumber}) are empty on creation and
 * filled later by infrastructure once a fiscal document is issued; their absence does not imply the
 * absence of a fiscal document.
 *
 * <p><strong>R4 note:</strong> this record does not validate payment authorisation for electronic
 * methods (CARD_DEBIT, CARD_CREDIT, PIX) — that responsibility lies with the UI layer.
 */
public record Sale(
        UUID saleId,
        String terminalId,
        String tenantId,
        String companyId,
        String branchId,
        String operatorId,
        String operatorName,
        SaleStatus status,
        String currency,
        Money subtotalAmount,
        Money discountAmount,
        Money totalAmount,
        SalePayment payment,
        List<SaleItem> items,
        Instant confirmedAt,
        Optional<Instant> cancelledAt,
        Optional<String> cancellationReason,
        Optional<String> fiscalDocumentType,
        Optional<String> fiscalNumber,
        Instant createdAt
) {

    public Sale {
        Objects.requireNonNull(saleId, "saleId must not be null");
        terminalId = requireNonBlank(terminalId, "terminalId");
        tenantId = requireNonBlank(tenantId, "tenantId");
        companyId = requireNonBlank(companyId, "companyId");
        branchId = requireNonBlank(branchId, "branchId");
        operatorId = requireNonBlank(operatorId, "operatorId");
        operatorName = requireNonBlank(operatorName, "operatorName");
        Objects.requireNonNull(status, "status must not be null");
        currency = requireNonBlank(currency, "currency");
        Objects.requireNonNull(subtotalAmount, "subtotalAmount must not be null");
        Objects.requireNonNull(discountAmount, "discountAmount must not be null");
        Objects.requireNonNull(totalAmount, "totalAmount must not be null");
        Objects.requireNonNull(payment, "payment must not be null");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        items = List.copyOf(items);
        Objects.requireNonNull(confirmedAt, "confirmedAt must not be null");
        cancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");
        cancellationReason = Objects.requireNonNull(cancellationReason, "cancellationReason must not be null");
        fiscalDocumentType = Objects.requireNonNull(fiscalDocumentType, "fiscalDocumentType must not be null");
        fiscalNumber = Objects.requireNonNull(fiscalNumber, "fiscalNumber must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        requireCurrency(subtotalAmount, currency, "subtotalAmount");
        requireCurrency(discountAmount, currency, "discountAmount");
        requireCurrency(totalAmount, currency, "totalAmount");
        requireCurrency(payment.paymentAmount(), currency, "payment.paymentAmount");
        requireCurrency(payment.changeAmount(), currency, "payment.changeAmount");

        Money itemsSubtotal = Money.zero(currency);
        for (SaleItem item : items) {
            Objects.requireNonNull(item, "items must not contain null");
            requireCurrency(item.unitPrice(), currency, "item.unitPrice");
            requireCurrency(item.discountAmount(), currency, "item.discountAmount");
            requireCurrency(item.totalAmount(), currency, "item.totalAmount");
            itemsSubtotal = itemsSubtotal.add(item.totalAmount());
        }
        if (itemsSubtotal.amount().compareTo(subtotalAmount.amount()) != 0) {
            throw new IllegalArgumentException(
                    "subtotalAmount does not match the sum of item totals");
        }

        Money expectedTotal = subtotalAmount.subtract(discountAmount);
        if (expectedTotal.amount().compareTo(totalAmount.amount()) != 0) {
            throw new IllegalArgumentException(
                    "totalAmount mismatch: subtotal(" + subtotalAmount.toPlainString()
                    + ") - discount(" + discountAmount.toPlainString()
                    + ") = " + expectedTotal.toPlainString()
                    + " but totalAmount = " + totalAmount.toPlainString());
        }

        if (payment.method() == PaymentMethod.CASH) {
            if (payment.paymentAmount().amount().compareTo(totalAmount.amount()) < 0) {
                throw new IllegalArgumentException("cash payment is insufficient for totalAmount");
            }
            Money expectedChange = payment.paymentAmount().subtract(totalAmount);
            if (expectedChange.amount().compareTo(payment.changeAmount().amount()) != 0) {
                throw new IllegalArgumentException("changeAmount does not match cash tender");
            }
        } else if (payment.paymentAmount().amount().compareTo(totalAmount.amount()) != 0
                || !payment.changeAmount().isZero()) {
            throw new IllegalArgumentException(
                    "non-cash payment must equal totalAmount and have no change");
        }

        if (status == SaleStatus.CANCELLED && cancelledAt.isEmpty()) {
            throw new IllegalArgumentException("cancelledAt is required when status is CANCELLED");
        }
        if (status == SaleStatus.CANCELLED) {
            if (cancellationReason.isEmpty() || cancellationReason.get().isBlank()) {
                throw new IllegalArgumentException(
                        "cancellationReason is required when status is CANCELLED");
            }
            if (cancelledAt.get().isBefore(confirmedAt)) {
                throw new IllegalArgumentException("cancelledAt must not be before confirmedAt");
            }
        }
        if (status == SaleStatus.CONFIRMED && cancelledAt.isPresent()) {
            throw new IllegalArgumentException("cancelledAt must be absent when status is CONFIRMED");
        }
        if (status == SaleStatus.CONFIRMED && cancellationReason.isPresent()) {
            throw new IllegalArgumentException(
                    "cancellationReason must be absent when status is CONFIRMED");
        }
    }

    /**
     * Creates a confirmed sale, computing {@code subtotalAmount = sum(item.totalAmount)} and
     * {@code totalAmount = subtotalAmount - discountAmount}.
     *
     * <p>Line numbers in the provided items are used as-is; the caller is responsible for assigning
     * them (1-based, sequential).
     *
     * @param items          non-empty list of line items, each already created via
     *                       {@link SaleItem#of}; all items must use {@code currency}
     * @param discountAmount sale-level discount applied after summing item totals
     */
    public static Sale confirm(
            UUID saleId,
            String terminalId,
            String tenantId,
            String companyId,
            String branchId,
            String operatorId,
            String operatorName,
            String currency,
            List<SaleItem> items,
            Money discountAmount,
            SalePayment payment,
            Instant confirmedAt) {
        Objects.requireNonNull(items, "items must not be null");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        Money subtotal = items.stream()
                .map(SaleItem::totalAmount)
                .reduce(Money.zero(currency), Money::add);
        Money total = subtotal.subtract(discountAmount);
        return new Sale(saleId, terminalId, tenantId, companyId, branchId,
                operatorId, operatorName, SaleStatus.CONFIRMED, currency,
                subtotal, discountAmount, total, payment, items,
                confirmedAt,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                confirmedAt);
    }

    /**
     * Returns a new {@code Sale} with {@link SaleStatus#CANCELLED} status. The original instance is
     * not modified.
     *
     * @param reason      non-blank cancellation reason
     * @param cancelledAt instant at which the cancellation occurred
     * @throws IllegalStateException    if this sale is not {@link SaleStatus#CONFIRMED}
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public Sale cancel(String reason, Instant cancelledAt) {
        if (status != SaleStatus.CONFIRMED) {
            throw new IllegalStateException("only a CONFIRMED sale can be cancelled; current status: " + status);
        }
        Objects.requireNonNull(cancelledAt, "cancelledAt must not be null");
        Objects.requireNonNull(reason, "cancellation reason must not be null");
        String trimmedReason = reason.strip();
        if (trimmedReason.isEmpty()) {
            throw new IllegalArgumentException("cancellation reason must not be blank");
        }
        return new Sale(saleId, terminalId, tenantId, companyId, branchId,
                operatorId, operatorName, SaleStatus.CANCELLED, currency,
                subtotalAmount, discountAmount, totalAmount, payment, items,
                confirmedAt,
                Optional.of(cancelledAt), Optional.of(trimmedReason),
                fiscalDocumentType, fiscalNumber,
                createdAt);
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return stripped;
    }

    private static void requireCurrency(Money money, String expected, String label) {
        if (!money.currency().equals(expected)) {
            throw new IllegalArgumentException(label + " currency must be " + expected
                    + " but got " + money.currency());
        }
    }
}
