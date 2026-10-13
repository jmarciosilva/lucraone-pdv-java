package br.com.lucraone.pdv.application.sale;

/** Local commercial operations for which the current terminal/operator context must be authorized. */
public enum SaleAuthorizationAction {
    CREATE_SALE,
    CONFIRM_MANUAL_PAYMENT,
    READ_SALES,
    CANCEL_SALE
}
