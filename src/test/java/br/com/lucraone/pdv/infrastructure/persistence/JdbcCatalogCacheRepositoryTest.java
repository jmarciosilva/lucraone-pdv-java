package br.com.lucraone.pdv.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.catalog.*;
import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class JdbcCatalogCacheRepositoryTest {
    @TempDir Path temp;
    private JdbcCatalogCacheRepository repository;
    private JdbcTerminalRepository terminals;
    private SqliteConnectionFactory connections;
    private final CatalogCacheContext context = new CatalogCacheContext("terminal-1","tenant-1","company-1");
    private final Instant now=Instant.parse("2026-10-12T12:00:00Z");

    @BeforeEach void setUp() {
        var directory=new LocalDataDirectory(() -> new WindowsPlatformPaths(key -> temp.toString()));
        connections=new LocalDatabaseInitializer(directory).initialize();
        var tx=new JdbcTransactionManager(connections);
        repository=new JdbcCatalogCacheRepository(tx); terminals=new JdbcTerminalRepository(tx);
        terminals.ensureInstallation(new Installation(UUID.fromString("fd40d440-b972-4ee1-a182-7ad62ad3ec52"),now));
    }

    @Test void upsertsOnlyReturnedRowsPreservesLeadingZeroesAndIsolatesCompany() {
        CatalogProduct first=product("p1","00000123",List.of(new CatalogPrice(new BigDecimal("29.90"),"BRL")));
        CatalogProduct second=product("p2",null,List.of());
        repository.upsert(context,List.of(first,second),now);
        repository.upsert(context,List.of(product("p1","00000123",List.of())),now.plusSeconds(10));
        assertEquals(2,repository.findAll(context).size());
        assertEquals("00000123",repository.findAll(context).get(0).product().barcode());
        assertTrue(repository.findAll(context).stream().filter(row -> row.product().id().equals("p1"))
                .findFirst().orElseThrow().product().salePrices().isEmpty());
        assertTrue(repository.findAll(new CatalogCacheContext("terminal-1","tenant-2","company-1")).isEmpty());
        assertEquals("fd40d440-b972-4ee1-a182-7ad62ad3ec52",terminals.findInstallation().orElseThrow().installationId().toString());
    }

    @Test void persistsPackageFactorAndQueryTimestampAcrossRepositoryRecreation() {
        CatalogProduct product=new CatalogProduct("pkg-product","sku","0012","Package product","UN","active",List.of(),
                List.of(new CatalogPackage("pkg","Fardo","00012",new BigDecimal("6"))),Optional.of(now));
        repository.upsert(context,List.of(product),now);
        var reopened=new JdbcCatalogCacheRepository(new JdbcTransactionManager(new LocalDatabaseInitializer(
                new LocalDataDirectory(() -> new WindowsPlatformPaths(key -> temp.toString()))).initialize()));
        var row=reopened.findAll(context).getFirst();
        assertEquals("00012",row.product().packages().getFirst().barcode());
        assertEquals(new BigDecimal("6"),row.product().packages().getFirst().factor());
        assertEquals(now,row.queriedAt());
    }

    @Test void failedPageWriteRollsBackEveryProductInThatPageAndKeepsPreviousData() throws Exception {
        repository.upsert(context,List.of(product("p1","0001",List.of())),now);
        try (var connection=connections.openConnection(); var statement=connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_catalog_update BEFORE UPDATE ON pdv_catalog_product BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
        }
        assertThrows(RuntimeException.class,()->repository.upsert(context,
                List.of(new CatalogProduct("p1","sku-p1","0001","Changed","UN","active",List.of(),List.of(),Optional.of(now)),
                        product("p2","0002",List.of())),now.plusSeconds(20)));
        var rows=repository.findAll(context);
        assertEquals(1,rows.size()); assertEquals("Product p1",rows.getFirst().product().name());
    }

    private CatalogProduct product(String id,String barcode,List<CatalogPrice> prices) {
        return new CatalogProduct(id,"sku-"+id,barcode,"Product "+id,"UN","active",prices,List.of(),Optional.of(now));
    }
}
