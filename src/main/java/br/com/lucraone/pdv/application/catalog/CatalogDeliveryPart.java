package br.com.lucraone.pdv.application.catalog;

import java.util.Objects;

/** Exact NDJSON bytes returned for one snapshot part, including the server's part checksum. */
public record CatalogDeliveryPart(int part, int partCount, String checksum, byte[] content) {
    public CatalogDeliveryPart {
        Objects.requireNonNull(checksum);
        content = Objects.requireNonNull(content).clone();
        if (part < 0 || partCount < 1 || part >= partCount || !checksum.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Invalid catalog part.");
        }
    }
    @Override public byte[] content() { return content.clone(); }
}
