package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.failureOf;
import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PdvCatalogDistributionContractTest {
    private static MachineCredential machine() { return new MachineCredential("Bearer", "synthetic-machine", Instant.parse("2027-01-01T00:00:00Z")); }
    private static PdvApiSettings settings() { return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT); }

    @Test void usesMachineOnlyAuthAndReadsPendingManifestNdjsonAndAcknowledgements() throws Exception {
        String ndjson = "{\"id\":\"01\",\"sku\":\"001\",\"name\":\"Café\",\"barcode\":\"000123\",\"unit\":\"UN\",\"status\":\"active\",\"sale_prices\":[],\"packages\":[],\"updated_at\":null}\n";
        String partHash = sha256(ndjson.getBytes(StandardCharsets.UTF_8));
        String pending = "{\"data\":[{\"delivery_id\":\"del-1\",\"publication_id\":\"pub-1\",\"version\":3,\"status\":\"AVAILABLE\",\"manifest_url\":\"/api/v1/pdv/catalog/deliveries/del-1/manifest\"}]}";
        String manifest = "{\"data\":{\"delivery_id\":\"del-1\",\"publication_id\":\"pub-1\",\"version\":3,\"schema_version\":1,\"checksum_algorithm\":\"sha256\",\"checksum\":\""+partHash+"\",\"product_count\":1,\"price_count\":0,\"package_count\":0,\"part_size\":200,\"part_count\":1,\"resume_from_part\":0}}";
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, pending));
            backend.enqueue(StubResponse.json(200, manifest));
            backend.enqueue(StubResponse.of(200, "application/x-ndjson; charset=utf-8", ndjson)
                    .withHeader("X-PDV-Catalog-Part", "0").withHeader("X-PDV-Catalog-Part-Count", "1")
                    .withHeader("X-PDV-Catalog-Part-Checksum", partHash));
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"status\":\"TRANSFERRING\"}}"));
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"status\":\"RECEIVED\"}}"));
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"status\":\"APPLIED\"}}"));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                var deliveries = gateway.pendingCatalogDeliveries(machine()).join();
                assertEquals("del-1", deliveries.getFirst().deliveryId());
                assertEquals(3, deliveries.getFirst().version());
                var snapshot = gateway.catalogDeliveryManifest(machine(), "del-1").join();
                assertEquals(1, snapshot.partCount());
                assertEquals(0, snapshot.resumeFromPart());
                var part = gateway.catalogDeliveryPart(machine(), "del-1", 0).join();
                assertEquals(partHash, part.checksum());
                assertEquals(ndjson, new String(part.content(), StandardCharsets.UTF_8));
                gateway.reportCatalogDeliveryProgress(machine(), "del-1", 0).join();
                gateway.confirmCatalogDeliveryReceived(machine(), "del-1").join();
                gateway.confirmCatalogDeliveryApplied(machine(), "del-1").join();
            }
            for (var request : backend.requests()) {
                assertEquals("Bearer synthetic-machine", request.header("authorization").orElseThrow());
                assertFalse(request.header("x-pdv-operator-session").isPresent());
                assertFalse(request.path().contains("synthetic-machine"));
            }
            assertEquals("{\"last_part\":0}", backend.requests().get(3).body());
            assertEquals("/api/v1/pdv/catalog/deliveries/del-1/applied", backend.requests().getLast().path());
        }
    }

    @Test void rejectsMalformedManifestAndBadNdjsonPartHeaders() {
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"delivery_id\":\"del\",\"publication_id\":\"pub\",\"version\":1,\"schema_version\":1,\"checksum_algorithm\":\"sha1\",\"checksum\":\"bad\",\"product_count\":0,\"price_count\":0,\"package_count\":0,\"part_size\":200,\"part_count\":0,\"resume_from_part\":0}}"));
            backend.enqueue(StubResponse.of(200, "application/json", "not NDJSON")
                    .withHeader("X-PDV-Catalog-Part", "0").withHeader("X-PDV-Catalog-Part-Count", "1")
                    .withHeader("X-PDV-Catalog-Part-Checksum", "0".repeat(64)));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(), settings())) {
                assertEquals(PdvFailureKind.INVALID_RESPONSE, failureOf(gateway.catalogDeliveryManifest(machine(), "del")).kind());
                assertEquals(PdvFailureKind.INVALID_RESPONSE, failureOf(gateway.catalogDeliveryPart(machine(), "del", 0)).kind());
            }
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
