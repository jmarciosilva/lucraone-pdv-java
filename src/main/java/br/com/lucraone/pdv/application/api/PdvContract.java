package br.com.lucraone.pdv.application.api;

/**
 * Single source of truth for the backend contract version this client was written against.
 * The transport prefix is derived from it by the infrastructure adapter, so the version
 * string exists in exactly one place.
 */
public final class PdvContract {

    /** Contract version supported by this build, as reported by the health endpoint. */
    public static final String SUPPORTED_VERSION = "v1";

    private PdvContract() {
    }
}
