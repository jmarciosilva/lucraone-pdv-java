package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.catalog.*;
import br.com.lucraone.pdv.infrastructure.api.dto.CatalogProductDto;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Strict decoder for the JSON-lines snapshot contract returned by the backend. */
public final class NdjsonCatalogSnapshotDecoder implements CatalogSnapshotDecoder {
    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Override public List<CatalogProduct> decode(List<byte[]> ndjsonParts) {
        List<CatalogProduct> result = new ArrayList<>();
        for (byte[] bytes : ndjsonParts) {
            String part = new String(bytes, StandardCharsets.UTF_8);
            if (!part.isEmpty() && !part.endsWith("\n")) throw new IllegalArgumentException("Catalog part is not newline terminated.");
            String[] lines = part.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i == lines.length - 1 && lines[i].isEmpty()) continue;
                if (lines[i].isBlank()) throw new IllegalArgumentException("Catalog snapshot contains an empty record.");
                try { result.add(product(mapper.readValue(lines[i], CatalogProductDto.class))); }
                catch (Exception exception) { throw new IllegalArgumentException("Catalog snapshot record is invalid.", exception); }
            }
        }
        return List.copyOf(result);
    }

    private static CatalogProduct product(CatalogProductDto dto) {
        List<CatalogPrice> prices = Optional.ofNullable(dto.salePrices()).orElseThrow().stream()
                .map(p -> new CatalogPrice(new BigDecimal(p.amount()), p.currency())).toList();
        List<CatalogPackage> packages = Optional.ofNullable(dto.packages()).orElseThrow().stream()
                .map(p -> new CatalogPackage(p.id(), p.name(), p.barcode(), p.factor())).toList();
        return new CatalogProduct(required(dto.id()), dto.sku(), dto.barcode(), required(dto.name()), required(dto.unit()),
                required(dto.status()), prices, packages,
                Optional.ofNullable(dto.updatedAt()).map(Instant::parse));
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Required catalog value is missing.");
        return value;
    }
}
