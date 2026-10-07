package br.com.lucraone.pdv.application;

/**
 * Informações básicas da aplicação usadas pela camada de apresentação.
 */
public final class ApplicationMetadata {

    private static final String NAME = "LucraOne PDV";

    private ApplicationMetadata() {
    }

    public static String name() {
        return NAME;
    }
}
