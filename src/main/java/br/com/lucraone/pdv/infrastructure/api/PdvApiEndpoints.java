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

    private PdvApiEndpoints() {
    }
}
