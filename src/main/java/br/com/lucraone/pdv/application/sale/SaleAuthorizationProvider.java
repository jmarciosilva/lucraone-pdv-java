package br.com.lucraone.pdv.application.sale;

/**
 * Application boundary for sale permission. A production implementation must validate terminal
 * identity, an authenticated operator and an explicit offline-sales grant or policy. No such grant
 * exists in the current authentication contracts, so the supplied default denies every operation.
 */
@FunctionalInterface
public interface SaleAuthorizationProvider {

    AuthorizedSaleContext requireAuthorizedContext(SaleAuthorizationAction action);

    static SaleAuthorizationProvider unavailable() {
        return action -> {
            throw new IllegalStateException("Local sale authorization is not configured.");
        };
    }
}
