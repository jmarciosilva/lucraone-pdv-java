package br.com.lucraone.pdv.infrastructure.auth;

import br.com.lucraone.pdv.application.auth.TerminalAuthenticationService;
import br.com.lucraone.pdv.application.operator.OperatorSessionService;
import br.com.lucraone.pdv.infrastructure.api.HttpPdvBackendGateway;
import java.util.Optional;

/** Runtime resources, closed on the worker rather than the JavaFX thread. */
public record TerminalRuntime(TerminalAuthenticationService authentication, boolean backendConfigured,
        Optional<HttpPdvBackendGateway> gateway, Optional<OperatorSessionService> operator)
        implements AutoCloseable {
    @Override public void close() { gateway.ifPresent(HttpPdvBackendGateway::close); }
    @Override public String toString() { return "TerminalRuntime[protected]"; }
}
