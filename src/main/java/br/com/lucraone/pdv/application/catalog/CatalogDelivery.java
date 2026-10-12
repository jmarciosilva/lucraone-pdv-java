package br.com.lucraone.pdv.application.catalog;

import java.util.Objects;

/** A server-side catalog delivery addressed to this machine credential. */
public record CatalogDelivery(String deliveryId, String publicationId, long version, String status, String manifestUrl) {
    public CatalogDelivery {
        Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(publicationId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(manifestUrl);
        if (deliveryId.isBlank() || publicationId.isBlank() || version < 1 || manifestUrl.isBlank()) {
            throw new IllegalArgumentException("Invalid catalog delivery.");
        }
    }
}
