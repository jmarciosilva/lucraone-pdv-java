package br.com.lucraone.pdv.infrastructure.demo;

import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.application.catalog.CatalogCacheContext;
import br.com.lucraone.pdv.application.sale.AuthorizedSaleContext;
import br.com.lucraone.pdv.application.sale.ElectronicPaymentAuthorizer;
import br.com.lucraone.pdv.application.sale.SaleService;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcActiveCatalogRepository;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcSaleRepository;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcTransactionManager;
import br.com.lucraone.pdv.infrastructure.persistence.LocalDataDirectory;
import br.com.lucraone.pdv.infrastructure.persistence.LocalDatabaseInitializer;
import br.com.lucraone.pdv.infrastructure.persistence.SqliteConnectionFactory;
import br.com.lucraone.pdv.infrastructure.platform.WindowsPlatformPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Isolated synthetic demo composition. It is deliberately never used by the operational runtime. */
public final class DemoSalesEnvironment implements AutoCloseable {

    private static final String TERMINAL = "DEMO-TERMINAL";
    private static final String TENANT = "DEMO-TENANT";
    private static final String COMPANY = "DEMO-COMPANY";
    private static final String BRANCH = "DEMO-BRANCH";
    private final Path temporaryRoot;
    private final SqliteConnectionFactory connections;
    private final JdbcTransactionManager transactions;
    private final JdbcActiveCatalogRepository catalog;
    private final JdbcSaleRepository sales;
    private final SaleService saleService;
    private final CatalogCacheContext catalogContext = new CatalogCacheContext(TERMINAL, TENANT, COMPANY);

    private DemoSalesEnvironment(Path temporaryRoot, SqliteConnectionFactory connections) {
        this.temporaryRoot = temporaryRoot;
        this.connections = connections;
        this.transactions = new JdbcTransactionManager(connections);
        this.catalog = new JdbcActiveCatalogRepository(transactions);
        this.sales = new JdbcSaleRepository(transactions);
        this.saleService = new SaleService(sales, catalog, action -> new AuthorizedSaleContext(
                new TerminalContext(
                        new TerminalContext.Terminal(TERMINAL, "Caixa demonstração", "active", "DEMO-INSTALL"),
                        new TerminalContext.Tenant(TENANT, "Empresa demonstração"),
                        new TerminalContext.Company(COMPANY, "LucraOne Demo"),
                        new TerminalContext.Branch(BRANCH, "Loja teste", "DEMO")),
                new OperatorSessionData("DEMO-SESSION", "active", Instant.now().plusSeconds(3600),
                        "DEMO-OPERATOR", "Operador Demonstração", "demo", "demo@example.invalid"), "BRL"),
                ElectronicPaymentAuthorizer.unavailable(), Clock.systemUTC());
    }

    public static DemoSalesEnvironment create() throws IOException {
        Path root = Files.createTempDirectory("lucraone-pdv-sales-demo-");
        try {
            LocalDataDirectory directory = new LocalDataDirectory(
                    new WindowsPlatformPaths(key -> root.toString()));
            SqliteConnectionFactory connections = new LocalDatabaseInitializer(directory).initialize();
            DemoSalesEnvironment environment = new DemoSalesEnvironment(root, connections);
            environment.seedCatalog();
            return environment;
        } catch (RuntimeException failure) {
            deleteTree(root);
            throw failure;
        }
    }

    private void seedCatalog() {
        transactions.inTransaction((connection, transaction) -> {
            Instant now = Instant.now();
            try (PreparedStatement version = connection.prepareStatement("""
                    INSERT INTO pdv_catalog_snapshot_version
                        (terminal_id,tenant_id,company_id,version,delivery_id,checksum,
                         product_count,price_count,package_count,applied_at)
                    VALUES (?,?,?,1,'demo-catalog-v1','synthetic-demo-v1',5,5,0,?)
                    """)) {
                version.setString(1, TERMINAL); version.setString(2, TENANT); version.setString(3, COMPANY);
                version.setString(4, now.toString()); version.executeUpdate();
            }
            List<DemoProduct> products = List.of(
                    new DemoProduct("DEMO-001", "7890000000001", "Cafe coado", "UN", "4.50"),
                    new DemoProduct("DEMO-002", "7890000000002", "Pao de queijo", "UN", "3.25"),
                    new DemoProduct("DEMO-003", "7890000000003", "Refrigerante 350 ml", "UN", "6.00"),
                    new DemoProduct("DEMO-004", "7890000000004", "Hamburguer classico", "UN", "18.90"),
                    new DemoProduct("DEMO-005", "7890000000005", "Batata frita", "PORCAO", "12.00"));
            try (PreparedStatement product = connection.prepareStatement("""
                        INSERT INTO pdv_catalog_snapshot_product
                            (terminal_id,tenant_id,company_id,version,product_id,sku,barcode,name,unit,status,updated_at)
                        VALUES (?,?,?,1,?,?,?, ?,?,'active',?)
                        """);
                 PreparedStatement price = connection.prepareStatement("""
                        INSERT INTO pdv_catalog_snapshot_price
                            (terminal_id,tenant_id,company_id,version,product_id,currency,amount)
                        VALUES (?,?,?,1,?,'BRL',?)
                        """)) {
                for (DemoProduct item : products) {
                    product.setString(1, TERMINAL); product.setString(2, TENANT); product.setString(3, COMPANY);
                    product.setString(4, item.id()); product.setString(5, item.id());
                    product.setString(6, item.barcode()); product.setString(7, item.name());
                    product.setString(8, item.unit()); product.setString(9, now.toString()); product.executeUpdate();
                    price.setString(1, TERMINAL); price.setString(2, TENANT); price.setString(3, COMPANY);
                    price.setString(4, item.id()); price.setString(5, item.price()); price.executeUpdate();
                }
            }
            try (PreparedStatement activate = connection.prepareStatement("""
                    INSERT INTO pdv_catalog_snapshot_active
                        (terminal_id,tenant_id,company_id,version,activated_at) VALUES (?,?,?,1,?)
                    """)) {
                activate.setString(1, TERMINAL); activate.setString(2, TENANT); activate.setString(3, COMPANY);
                activate.setString(4, now.toString()); activate.executeUpdate();
            }
            return null;
        });
    }

    public CatalogCacheContext catalogContext() { return catalogContext; }
    public JdbcActiveCatalogRepository catalog() { return catalog; }
    public JdbcSaleRepository sales() { return sales; }
    public SaleService saleService() { return saleService; }
    public SqliteConnectionFactory connections() { return connections; }
    public Path temporaryRoot() { return temporaryRoot; }

    @Override public void close() throws IOException { deleteTree(temporaryRoot); }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private record DemoProduct(String id, String barcode, String name, String unit, String price) { }
}
