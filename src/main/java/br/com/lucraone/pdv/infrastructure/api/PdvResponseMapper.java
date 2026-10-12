package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.api.BackendHealth;
import br.com.lucraone.pdv.application.catalog.BarcodeResolution;
import br.com.lucraone.pdv.application.catalog.CatalogPackage;
import br.com.lucraone.pdv.application.catalog.CatalogPage;
import br.com.lucraone.pdv.application.catalog.CatalogPrice;
import br.com.lucraone.pdv.application.catalog.CatalogProduct;
import br.com.lucraone.pdv.application.api.CredentialRotationResult;
import br.com.lucraone.pdv.application.api.CurrentTerminal;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorCredential;
import br.com.lucraone.pdv.application.api.OperatorInfo;
import br.com.lucraone.pdv.application.api.OperatorListResult;
import br.com.lucraone.pdv.application.api.OperatorLoginResult;
import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.PairedTerminal;
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvApiFailure;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.infrastructure.api.dto.BranchDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CompanyDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CredentialDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CredentialRotationResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CatalogProductDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CatalogProductsResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.BarcodeResolutionResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CredentialRecoveryResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CredentialLifecycleConfirmResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CurrentTerminalResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.HealthResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.OperatorDataDto;
import br.com.lucraone.pdv.infrastructure.api.dto.OperatorDto;
import br.com.lucraone.pdv.infrastructure.api.dto.OperatorSessionCredentialDto;
import br.com.lucraone.pdv.infrastructure.api.dto.OperatorSessionDto;
import br.com.lucraone.pdv.infrastructure.api.dto.OperatorSessionGetResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.OperatorSessionResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.OperatorsListResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.PairTerminalResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.TenantDto;
import br.com.lucraone.pdv.infrastructure.api.dto.TerminalDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns transport DTOs into application models. A successful status carrying a body that cannot be
 * understood, or that omits a field the contract requires, is reported as
 * {@link PdvFailureKind#INVALID_RESPONSE} rather than accepted as a partial success.
 *
 * <p>No DTO ever leaves this class, so Jackson types never reach the application or the presentation.
 */
final class PdvResponseMapper {

    private final ObjectMapper objectMapper;

    PdvResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    BackendHealth health(String body, String requestId) {
        HealthResponseDto dto = read(body, HealthResponseDto.class, requestId);
        HealthResponseDto.Data data = required(dto.data(), requestId);

        return new BackendHealth(
                required(data.status(), requestId),
                required(data.api(), requestId),
                required(data.version(), requestId),
                requestId
        );
    }

    PairedTerminal pairedTerminal(String body, String requestId) {
        PairTerminalResponseDto dto = read(body, PairTerminalResponseDto.class, requestId);
        PairTerminalResponseDto.Data data = required(dto.data(), requestId);
        CredentialDto credential = required(data.credential(), requestId);

        return new PairedTerminal(
                context(data.terminal(), data.tenant(), data.company(), data.branch(), requestId),
                new MachineCredential(
                        required(credential.tokenType(), requestId),
                        required(credential.accessToken(), requestId),
                        instant(credential.expiresAt(), requestId)
                ),
                requestId
        );
    }

    CurrentTerminal currentTerminal(String body, String requestId) {
        CurrentTerminalResponseDto dto = read(body, CurrentTerminalResponseDto.class, requestId);
        CurrentTerminalResponseDto.Data data = required(dto.data(), requestId);
        CredentialDto credential = required(data.credential(), requestId);

        // The authenticated read deliberately returns no access token, only the expiry.
        return new CurrentTerminal(
                context(data.terminal(), data.tenant(), data.company(), data.branch(), requestId),
                instant(credential.expiresAt(), requestId),
                requestId
        );
    }

    void validateHeartbeat(String body, String requestId) {
        read(body, com.fasterxml.jackson.databind.JsonNode.class, requestId);
    }

    OperatorListResult operatorList(String body, String requestId) {
        OperatorsListResponseDto dto = read(body, OperatorsListResponseDto.class, requestId);
        OperatorsListResponseDto.Data data = required(dto.data(), requestId);
        String branchId = required(data.branchId(), requestId);
        List<OperatorDto> rawOperators = data.operators() != null ? data.operators() : List.of();
        List<OperatorInfo> operators = rawOperators.stream()
                .map(op -> new OperatorInfo(
                        required(op.id(), requestId),
                        required(op.displayName(), requestId),
                        required(op.username(), requestId)))
                .collect(Collectors.toUnmodifiableList());
        return new OperatorListResult(branchId, operators, requestId);
    }

    CatalogPage catalogProducts(String body, String requestId) {
        CatalogProductsResponseDto dto = read(body, CatalogProductsResponseDto.class, requestId);
        CatalogProductsResponseDto.MetaDto meta = required(dto.meta(), requestId);
        Integer perPage = required(meta.perPage(), requestId);
        Boolean hasMore = required(meta.hasMore(), requestId);
        List<CatalogProductDto> rows = required(dto.data(), requestId);
        try {
            List<CatalogProduct> products = rows.stream().map(row -> catalogProduct(required(row, requestId), requestId)).toList();
            return new CatalogPage(products, perPage, meta.nextCursor(), hasMore);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse(requestId);
        }
    }

    BarcodeResolution barcodeResolution(String body, String requestId, String requestedBarcode) {
        BarcodeResolutionResponseDto dto = read(body, BarcodeResolutionResponseDto.class, requestId);
        BarcodeResolutionResponseDto.Data data = required(dto.data(), requestId);
        CatalogProduct product = catalogProduct(required(data.product(), requestId), requestId);
        CatalogPackage pack = data.packageValue() == null ? null : catalogPackage(data.packageValue(), requestId);
        String barcode = required(data.barcode(), requestId);
        if (barcode.length() > 14 || !barcode.equals(requestedBarcode)) throw invalidResponse(requestId);
        try {
            return new BarcodeResolution(barcode, required(data.source(), requestId), required(data.quantity(), requestId),
                    required(data.unit(), requestId), product, pack);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse(requestId);
        }
    }

    private CatalogProduct catalogProduct(CatalogProductDto dto, String requestId) {
        List<CatalogProductDto.SalePriceDto> rawPrices = required(dto.salePrices(), requestId);
        List<CatalogPrice> prices = rawPrices.stream().map(price -> {
            try {
                return new CatalogPrice(new BigDecimal(required(price.amount(), requestId)),
                        required(price.currency(), requestId));
            } catch (RuntimeException exception) { throw invalidResponse(requestId); }
        }).toList();
        List<CatalogProductDto.PackageDto> rawPackages = required(dto.packages(), requestId);
        List<CatalogPackage> packages = rawPackages.stream().map(pack -> catalogPackage(pack, requestId)).toList();
        String barcode = dto.barcode();
        if (barcode != null && barcode.length() > 14) throw invalidResponse(requestId);
        return new CatalogProduct(required(dto.id(), requestId), dto.sku(), barcode,
                required(dto.name(), requestId), required(dto.unit(), requestId),
                required(dto.status(), requestId), prices, packages, Optional.ofNullable(optionalInstant(dto.updatedAt(), requestId)));
    }

    private CatalogPackage catalogPackage(CatalogProductDto.PackageDto dto, String requestId) {
        BigDecimal factor = required(dto.factor(), requestId);
        if (factor.signum() <= 0 || (dto.barcode() != null && dto.barcode().length() > 14)) throw invalidResponse(requestId);
        return new CatalogPackage(required(dto.id(), requestId), required(dto.name(), requestId), dto.barcode(), factor);
    }

    OperatorLoginResult operatorLogin(String body, String requestId) {
        OperatorSessionResponseDto dto = read(body, OperatorSessionResponseDto.class, requestId);
        OperatorSessionResponseDto.Data data = required(dto.data(), requestId);
        OperatorSessionData sessionData = sessionData(required(data.session(), requestId), required(data.operator(), requestId), requestId);
        OperatorCredential credential = operatorCredential(required(data.credential(), requestId), requestId);
        return new OperatorLoginResult(sessionData, credential, requestId);
    }

    OperatorSessionData operatorSession(String body, String requestId) {
        OperatorSessionGetResponseDto dto = read(body, OperatorSessionGetResponseDto.class, requestId);
        OperatorSessionGetResponseDto.Data data = required(dto.data(), requestId);
        return sessionData(required(data.session(), requestId), required(data.operator(), requestId), requestId);
    }

    OperatorSessionData revalidatedOperatorSession(String body, String requestId) {
        OperatorSessionResponseDto dto = read(body, OperatorSessionResponseDto.class, requestId);
        OperatorSessionResponseDto.Data data = required(dto.data(), requestId);
        return sessionData(required(data.session(), requestId), required(data.operator(), requestId), requestId);
    }

    CredentialRotationResult credentialRotation(String body, String requestId) {
        CredentialRotationResponseDto dto = read(body, CredentialRotationResponseDto.class, requestId);
        CredentialRotationResponseDto.Data data = required(dto.data(), requestId);
        CredentialRotationResponseDto.Rotation rotation = required(data.rotation(), requestId);
        CredentialDto cred = required(data.credential(), requestId);
        MachineCredential candidate = new MachineCredential(
                required(cred.tokenType(), requestId),
                required(cred.accessToken(), requestId),
                instant(cred.expiresAt(), requestId));
        return new CredentialRotationResult(
                required(rotation.id(), requestId),
                required(rotation.status(), requestId),
                instant(rotation.expiresAt(), requestId),
                candidate,
                requestId);
    }

    Instant credentialConfirm(String body, String requestId, String expectedOperationId) {
        CredentialLifecycleConfirmResponseDto dto = read(body, CredentialLifecycleConfirmResponseDto.class, requestId);
        CredentialLifecycleConfirmResponseDto.Data data = required(dto.data(), requestId);
        CredentialLifecycleConfirmResponseDto.Rotation rotation = required(data.rotation(), requestId);
        CredentialDto credential = required(data.credential(), requestId);
        if (!required(rotation.id(), requestId).equals(expectedOperationId)) throw invalidResponse(requestId);
        if (!"CONFIRMED".equals(required(rotation.status(), requestId))) throw invalidResponse(requestId);
        instant(rotation.expiresAt(), requestId);
        if (!"Bearer".equals(required(credential.tokenType(), requestId))) throw invalidResponse(requestId);
        return instant(credential.expiresAt(), requestId);
    }

    CredentialRotationResult credentialRecovery(String body, String requestId) {
        CredentialRecoveryResponseDto dto = read(body, CredentialRecoveryResponseDto.class, requestId);
        CredentialRecoveryResponseDto.Data data = required(dto.data(), requestId);
        CredentialRecoveryResponseDto.Rotation rotation = required(data.rotation(), requestId);
        CredentialDto credential = required(data.credential(), requestId);
        MachineCredential candidate = new MachineCredential(required(credential.tokenType(), requestId),
                required(credential.accessToken(), requestId), instant(credential.expiresAt(), requestId));
        return new CredentialRotationResult(required(rotation.id(), requestId), required(rotation.status(), requestId),
                instant(rotation.expiresAt(), requestId), candidate, requestId);
    }

    private OperatorSessionData sessionData(OperatorSessionDto session, OperatorDataDto op, String requestId) {
        return new OperatorSessionData(
                required(session.id(), requestId),
                required(session.status(), requestId),
                optionalInstant(session.expiresAt(), requestId),
                required(op.id(), requestId),
                required(op.name(), requestId),
                required(op.username(), requestId),
                op.email());
    }

    private OperatorCredential operatorCredential(OperatorSessionCredentialDto dto, String requestId) {
        return new OperatorCredential(
                required(dto.tokenType(), requestId),
                required(dto.accessToken(), requestId),
                instant(dto.expiresAt(), requestId));
    }

    private TerminalContext context(
            TerminalDto terminal,
            TenantDto tenant,
            CompanyDto company,
            BranchDto branch,
            String requestId
    ) {
        TerminalDto safeTerminal = required(terminal, requestId);
        TenantDto safeTenant = required(tenant, requestId);
        CompanyDto safeCompany = required(company, requestId);
        BranchDto safeBranch = required(branch, requestId);

        return new TerminalContext(
                new TerminalContext.Terminal(
                        required(safeTerminal.id(), requestId),
                        required(safeTerminal.name(), requestId),
                        required(safeTerminal.status(), requestId),
                        required(safeTerminal.installationId(), requestId)
                ),
                new TerminalContext.Tenant(
                        required(safeTenant.id(), requestId),
                        required(safeTenant.name(), requestId)
                ),
                new TerminalContext.Company(
                        required(safeCompany.id(), requestId),
                        required(safeCompany.tradeName(), requestId)
                ),
                new TerminalContext.Branch(
                        required(safeBranch.id(), requestId),
                        required(safeBranch.name(), requestId),
                        required(safeBranch.code(), requestId)
                )
        );
    }

    private <T> T read(String body, Class<T> type, String requestId) {
        if (body == null || body.isBlank()) {
            throw invalidResponse(requestId);
        }
        try {
            T value = objectMapper.readValue(body, type);
            if (value == null) {
                throw invalidResponse(requestId);
            }
            return value;
        } catch (IOException | RuntimeException exception) {
            // The cause is dropped on purpose: Jackson messages quote the offending body.
            throw invalidResponse(requestId);
        }
    }

    private static <T> T required(T value, String requestId) {
        if (value == null) {
            throw invalidResponse(requestId);
        }
        return value;
    }

    private static String required(String value, String requestId) {
        if (value == null || value.isBlank()) {
            throw invalidResponse(requestId);
        }
        return value;
    }

    private static Instant instant(String raw, String requestId) {
        String value = required(raw, requestId);
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException offsetFailure) {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException instantFailure) {
                throw invalidResponse(requestId);
            }
        }
    }

    private static Instant optionalInstant(String raw, String requestId) {
        return raw == null ? null : instant(raw, requestId);
    }

    private static PdvApiException invalidResponse(String requestId) {
        return new PdvApiException(PdvApiFailure.of(PdvFailureKind.INVALID_RESPONSE, requestId));
    }
}
