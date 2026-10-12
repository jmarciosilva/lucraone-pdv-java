package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.catalog.CatalogCacheContext;
import br.com.lucraone.pdv.application.catalog.CatalogDistributionService;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcActiveCatalogRepository;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import br.com.lucraone.pdv.infrastructure.persistence.*;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogDistributionServiceIntegrationTest {
    @TempDir Path temp;

    @Test void downloadsVerifiesAndAtomicallyActivatesVersionedSnapshot() throws Exception {
        String ndjson = "{\"id\":\"prod-1\",\"sku\":\"001\",\"name\":\"Café\",\"barcode\":\"0001234567890\",\"unit\":\"UN\",\"status\":\"active\",\"sale_prices\":[{\"amount\":\"12.50\",\"currency\":\"BRL\"}],\"packages\":[{\"id\":\"pkg-1\",\"name\":\"Caixa\",\"barcode\":\"00000999\",\"factor\":6}],\"updated_at\":null}\n";
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ndjson.getBytes(StandardCharsets.UTF_8)));
        String pending = "{\"data\":[{\"delivery_id\":\"del-1\",\"publication_id\":\"pub-1\",\"version\":1,\"status\":\"AVAILABLE\",\"manifest_url\":\"/manifest\"}]}";
        String manifest = "{\"data\":{\"delivery_id\":\"del-1\",\"publication_id\":\"pub-1\",\"version\":1,\"schema_version\":1,\"checksum_algorithm\":\"sha256\",\"checksum\":\""+checksum+"\",\"product_count\":1,\"price_count\":1,\"package_count\":1,\"part_size\":200,\"part_count\":1,\"resume_from_part\":0}}";
        var directory = new LocalDataDirectory(new WindowsPlatformPaths(key -> temp.toString()));
        var connections = new LocalDatabaseInitializer(directory).initialize();
        var repository = new JdbcCatalogDeliveryRepository(new JdbcTransactionManager(connections));
        try (PdvTestBackend backend = PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200, pending));
            backend.enqueue(StubResponse.json(200, manifest));
            backend.enqueue(StubResponse.of(200, "application/x-ndjson; charset=utf-8", ndjson)
                    .withHeader("X-PDV-Catalog-Part", "0").withHeader("X-PDV-Catalog-Part-Count", "1")
                    .withHeader("X-PDV-Catalog-Part-Checksum", checksum));
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"status\":\"TRANSFERRING\"}}"));
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"status\":\"RECEIVED\"}}"));
            backend.enqueue(StubResponse.json(200, "{\"data\":{\"status\":\"APPLIED\"}}"));
            try (HttpPdvBackendGateway gateway = new HttpPdvBackendGateway(backend.baseUrl(),
                    PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT))) {
                var service = new CatalogDistributionService(gateway, repository, new NdjsonCatalogSnapshotDecoder(),
                        Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), java.time.ZoneOffset.UTC), Runnable::run);
                var report = service.synchronize(new MachineCredential("Bearer", "synthetic-machine",
                        Instant.parse("2027-01-01T00:00:00Z")), new CatalogCacheContext("terminal-1", "tenant-1", "company-1")).join();
                assertEquals(1, report.discovered()); assertEquals(1, report.applied());
            }
            try (var c = connections.openConnection(); var s = c.createStatement()) {
                try (var r = s.executeQuery("SELECT version FROM pdv_catalog_snapshot_active")) { assertTrue(r.next()); assertEquals(1, r.getInt(1)); }
                try (var r = s.executeQuery("SELECT barcode FROM pdv_catalog_snapshot_product")) { assertTrue(r.next()); assertEquals("0001234567890", r.getString(1)); }
                try (var r = s.executeQuery("SELECT amount FROM pdv_catalog_snapshot_price")) { assertTrue(r.next()); assertEquals("12.50", r.getString(1)); }
                try (var r = s.executeQuery("SELECT factor,barcode FROM pdv_catalog_snapshot_package")) { assertTrue(r.next()); assertEquals("6", r.getString(1)); assertEquals("00000999", r.getString(2)); }
            }
            var localCatalog = new JdbcActiveCatalogRepository(new JdbcTransactionManager(connections));
            var context = new br.com.lucraone.pdv.application.catalog.CatalogCacheContext("terminal-1", "tenant-1", "company-1");
            assertEquals("prod-1", localCatalog.search(context, "café", 20, null).products().getFirst().id());
            assertEquals("prod-1", localCatalog.search(context, "001", 20, null).products().getFirst().id());
            assertEquals("0001234567890", localCatalog.resolveBarcode(context, "0001234567890").barcode());
            var packageResolution = localCatalog.resolveBarcode(context, "00000999");
            assertEquals("package", packageResolution.source()); assertEquals("6", packageResolution.quantity().toPlainString());
            assertTrue(localCatalog.search(new br.com.lucraone.pdv.application.catalog.CatalogCacheContext(
                    "terminal-1", "tenant-other", "company-1"), "café", 20, null).products().isEmpty());
            assertEquals("Bearer synthetic-machine", backend.requests().getLast().header("authorization").orElseThrow());
            assertFalse(backend.requests().getLast().header("x-pdv-operator-session").isPresent());
        }
    }
}
