package br.com.lucraone.pdv.domain.sale;

/** Mirrors the {@code payment_method} CHECK constraint in V7 {@code pdv_sale}. */
public enum PaymentMethod {
    CASH,
    CARD_DEBIT,
    CARD_CREDIT,
    PIX,
    OTHER
}
