package br.com.lucraone.pdv.application.catalog;

import java.util.List;

/** Decodes the versioned catalog wire format after its byte checksums have passed. */
@FunctionalInterface
public interface CatalogSnapshotDecoder {
    List<CatalogProduct> decode(List<byte[]> ndjsonParts);
}
