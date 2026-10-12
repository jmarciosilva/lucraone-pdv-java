package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.catalog.*;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcCatalogDeliveryRepositoryTest {
    @TempDir Path temp;
    private JdbcCatalogDeliveryRepository repository;
    private SqliteConnectionFactory connections;
    private final CatalogCacheContext context = new CatalogCacheContext("terminal-1", "tenant-1", "company-1");
    private final Instant now = Instant.parse("2026-10-12T12:00:00Z");

    @BeforeEach void setUp() {
        var directory = new LocalDataDirectory(() -> new WindowsPlatformPaths(key -> temp.toString()));
        connections = new LocalDatabaseInitializer(directory).initialize();
        repository = new JdbcCatalogDeliveryRepository(new JdbcTransactionManager(connections));
    }

    @Test void persistsExactPartsAndActivatesVersionAtomicallyWithoutDestroyingPreviousVersion() throws Exception {
        var first = manifest("d1", 1, "1".repeat(64));
        repository.begin(context, first);
        byte[] bytes = "{}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        repository.storePart(context, "d1", new CatalogDeliveryPart(0, 1, sha256(bytes), bytes));
        assertEquals(0, repository.lastStoredPart(context, "d1"));
        assertArrayEquals(bytes, repository.parts(context, "d1").getFirst());
        repository.markReceived(context, "d1");
        repository.markApplied(context, first, List.of(product("p1", "000012", "29.90")), now);

        var second = manifest("d2", 2, "2".repeat(64));
        repository.begin(context, second);
        repository.markReceived(context, "d2");
        try (var connection = connections.openConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_snapshot_package BEFORE INSERT ON pdv_catalog_snapshot_package BEGIN SELECT RAISE(ABORT, 'synthetic apply failure'); END");
        }
        assertThrows(RuntimeException.class, () -> repository.markApplied(context, second,
                List.of(product("p2", "000099", "8.00")), now.plusSeconds(60)));
        try (var connection = connections.openConnection(); var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT version FROM pdv_catalog_snapshot_active WHERE terminal_id='terminal-1'")) {
            assertTrue(row.next()); assertEquals(1, row.getLong(1));
        }
        var recoverable = repository.recoverable(context);
        assertEquals(2, recoverable.size());
        assertEquals("APPLIED", recoverable.get(0).state());
        assertEquals("RECEIVED", recoverable.get(1).state());
    }

    private CatalogDeliveryManifest manifest(String id, long version, String checksum) {
        return new CatalogDeliveryManifest(id, "pub-" + id, version, 1, "sha256", checksum, 1, 1, 1, 200, 1, 0);
    }
    private CatalogProduct product(String id, String barcode, String amount) {
        return new CatalogProduct(id, "sku", barcode, "Product " + id, "UN", "active",
                List.of(new CatalogPrice(new BigDecimal(amount), "BRL")),
                List.of(new CatalogPackage("box", "Caixa", "0000", new BigDecimal("6"))), Optional.of(now));
    }
    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }
}
