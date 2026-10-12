package br.com.lucraone.pdv.infrastructure.auth;

import br.com.lucraone.pdv.application.auth.CredentialRotationService;
import br.com.lucraone.pdv.application.auth.CredentialRecoveryService;
import br.com.lucraone.pdv.application.auth.TerminalAuthenticationService;
import br.com.lucraone.pdv.application.operator.OperatorSessionService;
import br.com.lucraone.pdv.application.catalog.CatalogDistributionService;
import br.com.lucraone.pdv.application.catalog.OfflineCatalogQueryService;
import br.com.lucraone.pdv.application.auth.TerminalHeartbeatService;
import br.com.lucraone.pdv.infrastructure.api.HttpPdvBackendGateway;
import java.util.Optional;

/** Runtime resources, closed on the worker rather than the JavaFX thread. */
public record TerminalRuntime(TerminalAuthenticationService authentication, boolean backendConfigured,
        Optional<HttpPdvBackendGateway> gateway, Optional<OperatorSessionService> operator,
        Optional<CredentialRotationService> rotation, Optional<CredentialRecoveryService> recovery,
        Optional<OfflineCatalogQueryService> catalog, Optional<CatalogDistributionService> catalogDistribution,
        Optional<TerminalHeartbeatService> heartbeat)
        implements AutoCloseable {
    @Override public void close() {
        operator.ifPresent(OperatorSessionService::close);
        heartbeat.ifPresent(TerminalHeartbeatService::close);
        gateway.ifPresent(HttpPdvBackendGateway::close);
    }
    @Override public String toString() { return "TerminalRuntime[protected]"; }
}
