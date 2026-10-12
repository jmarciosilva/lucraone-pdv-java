package br.com.lucraone.pdv.application.catalog;

import java.util.Objects;

/** Immutable contract for a complete catalog snapshot. */
public record CatalogDeliveryManifest(String deliveryId, String publicationId, long version, int schemaVersion,
        String checksumAlgorithm, String checksum, int productCount, int priceCount, int packageCount,
        int partSize, int partCount, int resumeFromPart) {
    public CatalogDeliveryManifest {
        Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(publicationId);
        Objects.requireNonNull(checksumAlgorithm);
        Objects.requireNonNull(checksum);
        if (deliveryId.isBlank() || publicationId.isBlank() || version < 1 || schemaVersion < 1
                || !"sha256".equalsIgnoreCase(checksumAlgorithm) || !checksum.matches("[a-fA-F0-9]{64}")
                || productCount < 0 || priceCount < 0 || packageCount < 0 || partSize < 1 || partCount < 0
                || resumeFromPart < 0 || resumeFromPart > partCount
                || partCount != (productCount + partSize - 1) / partSize) {
            throw new IllegalArgumentException("Invalid catalog manifest.");
        }
    }
}
