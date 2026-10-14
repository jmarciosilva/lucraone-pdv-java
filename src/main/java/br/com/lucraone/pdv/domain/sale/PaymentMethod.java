package br.com.lucraone.pdv.domain.sale;

/**
 * Mirrors the {@code payment_method} CHECK constraint in {@code pdv_sale}, extended by V10 to cover
 * the six manual MVP modalities. {@code OTHER} remains disabled for sales.
 */
public enum PaymentMethod {
    CASH,
    CARD_DEBIT,
    CARD_CREDIT,
    PIX,
    MEAL_VOUCHER,
    FOOD_VOUCHER,
    OTHER
}
