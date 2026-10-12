package br.com.lucraone.pdv.application.catalog;

import java.util.Objects;

public record RecoverableCatalogDelivery(CatalogDeliveryManifest manifest, String state) {
    public RecoverableCatalogDelivery {
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(state);
        if (!state.equals("RECEIVED") && !state.equals("APPLIED")) throw new IllegalArgumentException("Delivery is not recoverable.");
    }
}
