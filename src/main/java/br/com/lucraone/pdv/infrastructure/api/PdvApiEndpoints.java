package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.api.PdvContract;

/**
 * The only place where PDV endpoint paths are written. The version segment is derived from
 * {@link PdvContract#SUPPORTED_VERSION}, so the contract version exists in a single location.
 */
final class PdvApiEndpoints {

    static final String PREFIX = "/api/" + PdvContract.SUPPORTED_VERSION + "/pdv";

    static final String HEALTH = PREFIX + "/health";
    static final String PAIR_TERMINAL = PREFIX + "/terminals/pair";
    static final String CURRENT_TERMINAL = PREFIX + "/terminal";
    static final String OPERATORS = PREFIX + "/operators";
    static final String CATALOG_PRODUCTS = PREFIX + "/catalog/products";
    static final String CATALOG_RESOLVE_BARCODE = PREFIX + "/catalog/resolve-barcode/";
    static final String CATALOG_DELIVERIES_PENDING = PREFIX + "/catalog/deliveries/pending";
    static final String CATALOG_DELIVERY = PREFIX + "/catalog/deliveries/";
    static final String OPERATOR_SESSIONS = PREFIX + "/operator-sessions";
    static final String OPERATOR_SESSION = PREFIX + "/operator-session";
    static final String OPERATOR_SESSION_REVALIDATE = PREFIX + "/operator-session/revalidate";
    static final String CREDENTIAL_ROTATIONS = PREFIX + "/terminal/credential/rotations";
    static final String CREDENTIAL_CONFIRM = PREFIX + "/terminal/credential/confirm";
    static final String CREDENTIAL_RECOVER = PREFIX + "/terminal/credential/recover";

    private PdvApiEndpoints() {
    }
}
