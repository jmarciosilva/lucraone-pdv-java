package br.com.lucraone.pdv.domain.sale;

/** Provenance of the payment confirmation persisted with a sale. */
public enum PaymentConfirmationOrigin {
    MANUAL,
    PROVIDER_VERIFIED,
    LEGACY_UNCLASSIFIED
}
