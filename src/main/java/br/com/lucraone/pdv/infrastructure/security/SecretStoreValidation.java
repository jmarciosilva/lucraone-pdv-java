package br.com.lucraone.pdv.infrastructure.security;

import java.util.Objects;

final class SecretStoreValidation {
    static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Identificador de segredo inválido.");
    }
    static void secret(byte[] secret) {
        Objects.requireNonNull(secret, "Segredo ausente.");
        if (secret.length == 0 || secret.length > 1024 * 1024)
            throw new IllegalArgumentException("Tamanho de segredo inválido.");
    }
}
