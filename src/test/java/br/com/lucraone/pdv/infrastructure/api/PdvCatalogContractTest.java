package br.com.lucraone.pdv.infrastructure.api;

import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.TEST_REQUEST_TIMEOUT;
import static br.com.lucraone.pdv.infrastructure.api.PdvApiAssertions.failureOf;
import static org.junit.jupiter.api.Assertions.*;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.infrastructure.api.PdvTestBackend.StubResponse;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PdvCatalogContractTest {
    private static final String PRODUCT = "{\"id\":\"01ABC\",\"sku\":\"SKU-01\",\"barcode\":\"0001234567890\",\"name\":\"Arroz\",\"unit\":\"KG\",\"status\":\"active\",\"sale_prices\":[{\"amount\":\"29.90\",\"currency\":\"BRL\"},{\"amount\":\"4.25\",\"currency\":\"USD\"}],\"packages\":[{\"id\":\"01PKG\",\"name\":\"Fardo 6\",\"barcode\":\"0009876543210\",\"factor\":6}],\"updated_at\":\"2026-10-12T12:00:00.000000Z\"}";
    private static MachineCredential machine() { return new MachineCredential("Bearer", "synthetic-machine", Instant.parse("2027-01-01T00:00:00Z")); }
    private static PdvApiSettings settings() { return PdvApiSettings.defaults().withRequestTimeout(TEST_REQUEST_TIMEOUT); }

    @Test void listsPageAndSendsBothIdentitiesAndOpaqueCursor() {
        try (PdvTestBackend backend=PdvTestBackend.start()) {
            String pageBody="{\"data\":["+PRODUCT+"],\"meta\":{\"per_page\":25,\"next_cursor\":\"opaque+/=\",\"has_more\":true}}";
            backend.enqueue(StubResponse.json(200,pageBody));
            backend.enqueue(StubResponse.json(200,pageBody));
            try (HttpPdvBackendGateway gateway=new HttpPdvBackendGateway(backend.baseUrl(),settings())) {
                var page=gateway.catalogProducts(machine(),"synthetic-operator","Café & arroz",25,null).join();
                assertEquals(1,page.products().size()); assertEquals("0001234567890",page.products().get(0).barcode());
                assertEquals(new BigDecimal("29.90"),page.products().get(0).salePrices().get(0).amount());
                assertEquals(new BigDecimal("6"),page.products().get(0).packages().get(0).factor());
                assertEquals("opaque+/=",page.nextCursor()); assertTrue(page.hasMore());
                gateway.catalogProducts(machine(),"synthetic-operator","Café & arroz",25,page.nextCursor()).join();
            }
            var first=backend.requests().get(0); var next=backend.requests().get(1);
            assertTrue(first.path().contains("per_page=25")); assertTrue(first.path().contains("search=Caf%C3%A9+%26+arroz"));
            assertTrue(next.path().contains("search=Caf%C3%A9+%26+arroz")); assertTrue(next.path().contains("cursor=opaque%2B%2F%3D"));
            assertEquals("Bearer synthetic-machine",first.header("authorization").orElseThrow());
            assertEquals("synthetic-operator",first.header("x-pdv-operator-session").orElseThrow());
            assertFalse(first.path().contains("synthetic-machine")); assertTrue(first.body().isEmpty());
        }
    }

    @Test void resolvesExactBarcodeAsPathStringPreservingLeadingZeroes() {
        try (PdvTestBackend backend=PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200,"{\"data\":{\"barcode\":\"0009876543210\",\"source\":\"package\",\"quantity\":6,\"unit\":\"KG\",\"product\":"+PRODUCT+",\"package\":{\"id\":\"01PKG\",\"name\":\"Fardo 6\",\"barcode\":\"0009876543210\",\"factor\":6}}}"));
            try (HttpPdvBackendGateway gateway=new HttpPdvBackendGateway(backend.baseUrl(),settings())) {
                var resolved=gateway.resolveBarcode(machine(),"synthetic-operator","0009876543210").join();
                assertEquals("0009876543210",resolved.barcode()); assertEquals(new BigDecimal("6"),resolved.quantity());
                assertEquals("package",resolved.source()); assertEquals("Fardo 6",resolved.commercialPackage().name());
            }
            assertTrue(backend.singleRequest().path().endsWith("/0009876543210"));
        }
    }

    @Test void classifiesBackendBarcodeNotFoundAndConflict() {
        try (PdvTestBackend backend=PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(404,PdvApiFixtures.error("product_not_found","not found")));
            backend.enqueue(StubResponse.json(409,PdvApiFixtures.error("barcode_conflict","conflict")));
            try (HttpPdvBackendGateway gateway=new HttpPdvBackendGateway(backend.baseUrl(),settings())) {
                assertEquals(PdvFailureKind.PRODUCT_NOT_FOUND,failureOf(gateway.resolveBarcode(machine(),"session","0001")).kind());
                assertEquals(PdvFailureKind.BARCODE_CONFLICT,failureOf(gateway.resolveBarcode(machine(),"session","0002")).kind());
            }
        }
    }

    @Test void rejectsMalformedCatalogRequiredFieldsAndInvalidPrices() {
        try (PdvTestBackend backend=PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200,"{\"data\":[{\"id\":\"01\"}],\"meta\":{\"per_page\":50,\"next_cursor\":null,\"has_more\":false}}"));
            try (HttpPdvBackendGateway gateway=new HttpPdvBackendGateway(backend.baseUrl(),settings())) {
                assertEquals(PdvFailureKind.INVALID_RESPONSE,
                        failureOf(gateway.catalogProducts(machine(),"session","arroz",50,null)).kind());
            }
        }
    }

    @Test void acceptsProductWithoutPriceAndNullableUpdatedAtWithoutInventingZero() {
        String product="{\"id\":\"01NP\",\"sku\":null,\"barcode\":null,\"name\":\"Sem preço\",\"unit\":\"UN\",\"status\":\"active\",\"sale_prices\":[],\"packages\":[],\"updated_at\":null}";
        try (PdvTestBackend backend=PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200,"{\"data\":["+product+"],\"meta\":{\"per_page\":50,\"next_cursor\":null,\"has_more\":false}}"));
            try (HttpPdvBackendGateway gateway=new HttpPdvBackendGateway(backend.baseUrl(),settings())) {
                var result=gateway.catalogProducts(machine(),"session","sem preço",50,null).join().products().getFirst();
                assertTrue(result.salePrices().isEmpty()); assertTrue(result.updatedAt().isEmpty());
                assertNull(result.sku()); assertNull(result.barcode());
            }
        }
    }

    @Test void mapsCatalogAuthorizationValidationRateLimitAndServerFailures() {
        try (PdvTestBackend backend=PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(401,PdvApiFixtures.error("unauthenticated","unauthenticated")));
            backend.enqueue(StubResponse.json(403,PdvApiFixtures.error("forbidden","forbidden")));
            backend.enqueue(StubResponse.json(422,PdvApiFixtures.error("validation_error","invalid")));
            backend.enqueue(StubResponse.json(429,PdvApiFixtures.error("rate_limited","slow down")));
            backend.enqueue(StubResponse.json(500,PdvApiFixtures.error("server_error","failed")));
            try (HttpPdvBackendGateway gateway=new HttpPdvBackendGateway(backend.baseUrl(),settings())) {
                assertEquals(PdvFailureKind.UNAUTHENTICATED,failureOf(gateway.catalogProducts(machine(),"s","x",10,null)).kind());
                assertEquals(PdvFailureKind.FORBIDDEN,failureOf(gateway.catalogProducts(machine(),"s","x",10,null)).kind());
                assertEquals(PdvFailureKind.VALIDATION,failureOf(gateway.catalogProducts(machine(),"s","x",10,null)).kind());
                assertEquals(PdvFailureKind.RATE_LIMITED,failureOf(gateway.catalogProducts(machine(),"s","x",10,null)).kind());
                assertEquals(PdvFailureKind.SERVER_ERROR,failureOf(gateway.catalogProducts(machine(),"s","x",10,null)).kind());
            }
        }
    }

    @Test void rejectsInvalidJsonAndInvalidProductTimestamp() {
        try (PdvTestBackend backend=PdvTestBackend.start()) {
            backend.enqueue(StubResponse.json(200,"{\"data\":["));
            String badTimestamp=PRODUCT.replace("2026-10-12T12:00:00.000000Z","yesterday-ish");
            backend.enqueue(StubResponse.json(200,"{\"data\":["+badTimestamp+"],\"meta\":{\"per_page\":50,\"next_cursor\":null,\"has_more\":false}}"));
            try (HttpPdvBackendGateway gateway=new HttpPdvBackendGateway(backend.baseUrl(),settings())) {
                assertEquals(PdvFailureKind.INVALID_RESPONSE,failureOf(gateway.catalogProducts(machine(),"s","x",50,null)).kind());
                assertEquals(PdvFailureKind.INVALID_RESPONSE,failureOf(gateway.catalogProducts(machine(),"s","x",50,null)).kind());
            }
        }
    }
}
