package br.com.lucraone.pdv.infrastructure.api;

import br.com.lucraone.pdv.application.api.BackendHealth;
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
import br.com.lucraone.pdv.infrastructure.api.dto.CredentialConfirmResponseDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CredentialDto;
import br.com.lucraone.pdv.infrastructure.api.dto.CredentialRotationResponseDto;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
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

    OperatorLoginResult operatorLogin(String body, String requestId) {
        OperatorSessionResponseDto dto = read(body, OperatorSessionResponseDto.class, requestId);
        OperatorSessionResponseDto.Data data = required(dto.data(), requestId);
        OperatorSessionData sessionData = sessionData(required(data.session(), requestId), requestId);
        OperatorCredential credential = operatorCredential(required(data.credential(), requestId), requestId);
        return new OperatorLoginResult(sessionData, credential, requestId);
    }

    OperatorSessionData operatorSession(String body, String requestId) {
        OperatorSessionGetResponseDto dto = read(body, OperatorSessionGetResponseDto.class, requestId);
        OperatorSessionGetResponseDto.Data data = required(dto.data(), requestId);
        return sessionData(required(data.session(), requestId), requestId);
    }

    OperatorSessionData revalidatedOperatorSession(String body, String requestId) {
        OperatorSessionResponseDto dto = read(body, OperatorSessionResponseDto.class, requestId);
        OperatorSessionResponseDto.Data data = required(dto.data(), requestId);
        return sessionData(required(data.session(), requestId), requestId);
    }

    CredentialRotationResult credentialRotation(String body, String requestId) {
        CredentialRotationResponseDto dto = read(body, CredentialRotationResponseDto.class, requestId);
        CredentialRotationResponseDto.Data data = required(dto.data(), requestId);
        CredentialDto cred = required(data.credential(), requestId);
        MachineCredential candidate = new MachineCredential(
                required(cred.tokenType(), requestId),
                required(cred.accessToken(), requestId),
                instant(cred.expiresAt(), requestId));
        return new CredentialRotationResult(
                required(data.operationId(), requestId),
                required(data.status(), requestId),
                instant(data.rotationExpiresAt(), requestId),
                candidate,
                requestId);
    }

    Instant credentialConfirm(String body, String requestId) {
        CredentialConfirmResponseDto dto = read(body, CredentialConfirmResponseDto.class, requestId);
        CredentialConfirmResponseDto.Data data = required(dto.data(), requestId);
        return instant(data.confirmedAt(), requestId);
    }

    private OperatorSessionData sessionData(OperatorSessionDto session, String requestId) {
        OperatorDataDto op = required(session.operator(), requestId);
        return new OperatorSessionData(
                required(session.id(), requestId),
                required(session.status(), requestId),
                instant(session.expiresAt(), requestId),
                required(op.id(), requestId),
                required(op.name(), requestId),
                required(op.email(), requestId));
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

    private static PdvApiException invalidResponse(String requestId) {
        return new PdvApiException(PdvApiFailure.of(PdvFailureKind.INVALID_RESPONSE, requestId));
    }
}
