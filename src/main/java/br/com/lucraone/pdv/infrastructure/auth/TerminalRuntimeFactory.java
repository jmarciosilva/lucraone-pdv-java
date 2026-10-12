package br.com.lucraone.pdv.infrastructure.auth;

import br.com.lucraone.pdv.application.auth.CredentialRotationService;
import br.com.lucraone.pdv.application.auth.CredentialRecoveryService;
import br.com.lucraone.pdv.application.auth.TerminalAuthenticationService;
import br.com.lucraone.pdv.application.operator.OperatorSessionService;
import br.com.lucraone.pdv.application.catalog.CatalogDistributionService;
import br.com.lucraone.pdv.application.catalog.OfflineCatalogQueryService;
import br.com.lucraone.pdv.infrastructure.api.NdjsonCatalogSnapshotDecoder;
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
        var secretStore = new LazySecretStore(SecretStoreFactory::current);
        var credentials = new SecretStoreMachineCredentialRepository(secretStore);
        var candidateCredentials = new SecretStoreCandidateMachineCredentialRepository(secretStore);
        var rotationCandidates = new SecretStoreCandidateMachineCredentialRepository(secretStore,
                "machine-credential-rotation-candidate-v1");
        var rotationProofs = new SecretStoreCredentialRotationProofRepository(secretStore);
        var recoveryProofs = new SecretStoreCredentialRecoveryRepository(secretStore);
        var rotations = new JdbcCredentialRotationRepository(transactions);
        String backendIdentity = baseUrl.map(uri -> PdvApiBaseUrl.of(uri).resolve("/").toString()).orElse("");
        var authentication = new TerminalAuthenticationService(terminals, credentials,
                new JdbcTerminalValidationRepository(transactions), gateway.map(value -> value), backendIdentity,
                clock, worker);
        Optional<OperatorSessionService> operatorService = gateway.map(
                gw -> new OperatorSessionService(gw, clock, worker, authentication::loadStoredCredential));
        Optional<CredentialRotationService> rotationService = gateway.map(
                gw -> new CredentialRotationService(credentials, rotationCandidates, rotations, rotationProofs, gw, clock));
        Optional<CredentialRecoveryService> recoveryService = gateway.map(
                gw -> new CredentialRecoveryService(credentials, candidateCredentials, recoveryProofs, gw,
                        authentication, clock, worker));
        Optional<OfflineCatalogQueryService> catalogService = Optional.of(
                new OfflineCatalogQueryService(new JdbcActiveCatalogRepository(transactions),
                        authentication::lastVerifiedContext, worker));
        var deliveryRepository = new JdbcCatalogDeliveryRepository(transactions);
        Optional<CatalogDistributionService> distributionService = gateway.map(
                gw -> new CatalogDistributionService(gw, deliveryRepository, new NdjsonCatalogSnapshotDecoder(), clock, worker));
        return new TerminalRuntime(authentication, gateway.isPresent(), gateway, operatorService, rotationService, recoveryService,
                catalogService, distributionService);
    }
}
