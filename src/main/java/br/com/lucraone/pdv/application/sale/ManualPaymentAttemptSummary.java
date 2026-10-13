package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import java.time.Instant;
import java.util.UUID;

/** Minimal audit projection for manual declarations that are not linked to a completed sale. */
public record ManualPaymentAttemptSummary(
        UUID confirmationId, UUID saleId, PaymentMethod method, Money amount, String state, Instant createdAt) { }
