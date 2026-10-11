package br.com.lucraone.pdv.infrastructure.auth;

import br.com.lucraone.pdv.application.auth.TerminalAuthenticationService;
import br.com.lucraone.pdv.application.operator.OperatorSessionService;
import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.infrastructure.api.HttpPdvBackendGateway;
import br.com.lucraone.pdv.infrastructure.api.PdvApiBaseUrl;
import br.com.lucraone.pdv.infrastructure.configuration.PropertiesBootstrapConfigurationSource;
import br.com.lucraone.pdv.infrastructure.persistence.*;
import br.com.lucraone.pdv.infrastructure.security.*;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Composition only. Invoke on the same serial worker used by the authentication coordinator. */
public final class TerminalRuntimeFactory {
    private TerminalRuntimeFactory() {}
    public static TerminalRuntime create(Executor worker) {
        var directory = new LocalDataDirectory();
        var connections = new LocalDatabaseInitializer(directory).initialize();
        var transactions = new JdbcTransactionManager(connections);
        var terminals = new JdbcTerminalRepository(transactions);
        var clock = Clock.systemUTC();
        if (terminals.findInstallation().isEmpty()) {
            terminals.ensureInstallation(new Installation(UUID.randomUUID(), clock.instant()));
        }
        var bootstrap = new PropertiesBootstrapConfigurationSource(directory.bootstrapConfigurationFile()).load();
        var baseUrl = bootstrap.flatMap(configuration -> configuration.apiBaseUrl());
        var gateway = baseUrl.map(HttpPdvBackendGateway::new);
        var credentials = new SecretStoreMachineCredentialRepository(new LazySecretStore(SecretStoreFactory::current));
        String backendIdentity = baseUrl.map(uri -> PdvApiBaseUrl.of(uri).resolve("/").toString()).orElse("");
        Optional<OperatorSessionService> operatorService = gateway.map(
                gw -> new OperatorSessionService(gw, clock, worker));
        return new TerminalRuntime(new TerminalAuthenticationService(terminals, credentials,
                new JdbcTerminalValidationRepository(transactions), gateway.map(value -> value), backendIdentity,
                clock, worker), gateway.isPresent(), gateway, operatorService);
    }
}
