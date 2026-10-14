package br.com.lucraone.pdv.application.sale;

/** Explicit operator declaration for the manual MVP payment flow. */
public enum ManualPaymentDeclaration {
    CASH_RECEIVED,
    EXTERNAL_CARD_APPROVED,
    PIX_OFFICIAL_CHANNEL_CHECKED,
    EXTERNAL_VOUCHER_APPROVED
}
