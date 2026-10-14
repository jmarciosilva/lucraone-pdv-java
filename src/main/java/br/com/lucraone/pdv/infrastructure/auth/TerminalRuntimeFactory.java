package br.com.lucraone.pdv.infrastructure.auth;

import br.com.lucraone.pdv.application.auth.CredentialRotationService;
import br.com.lucraone.pdv.application.auth.CredentialRecoveryService;
import br.com.lucraone.pdv.application.auth.CredentialRenewalService;
import br.com.lucraone.pdv.application.auth.TerminalAuthenticationService;
import br.com.lucraone.pdv.application.auth.TerminalHeartbeatService;
import br.com.lucraone.pdv.application.operator.OperatorSessionService;
import br.com.lucraone.pdv.application.catalog.CatalogDistributionService;
import br.com.lucraone.pdv.application.catalog.OfflineCatalogQueryService;
import br.com.lucraone.pdv.application.sale.ElectronicPaymentAuthorizer;
import br.com.lucraone.pdv.application.sale.OperatorTerminalSaleAuthorizationProvider;
import br.com.lucraone.pdv.application.sale.SaleService;
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
        var activeCatalog = new JdbcActiveCatalogRepository(transactions);
        Optional<OfflineCatalogQueryService> catalogService = Optional.of(
                new OfflineCatalogQueryService(activeCatalog, authentication::lastVerifiedContext, worker));
        var deliveryRepository = new JdbcCatalogDeliveryRepository(transactions);
        Optional<CatalogDistributionService> distributionService = gateway.map(
                gw -> new CatalogDistributionService(gw, deliveryRepository, new NdjsonCatalogSnapshotDecoder(), clock, worker));
        Optional<TerminalHeartbeatService> heartbeat = gateway.map(gw -> new TerminalHeartbeatService(gw,
                authentication::loadStoredCredential, authentication::restore, java.time.Duration.ofSeconds(
                        Math.max(5, Long.getLong("lucraone.pdv.heartbeat-seconds", 30L)))));
        Optional<CredentialRenewalService> renewalService = rotationService.map(
                rs -> new CredentialRenewalService(rs, authentication::loadStoredCredential,
                        clock, CredentialRenewalService.DEFAULT_LEAD_TIME, worker));
        // Operational sales reuse the same transaction manager (venda + itens + outbox are one SQLite
        // transaction). Authorization derives only from the verified terminal context, a non-expired
        // machine credential and the live operator session; no backend ability, no simulated identity.
        Optional<SaleService> saleService = operatorService.map(operator -> {
            var authorization = new OperatorTerminalSaleAuthorizationProvider(
                    authentication::lastVerifiedContext,
                    () -> {
                        try {
                            return authentication.loadStoredCredential();
                        } catch (RuntimeException unavailable) {
                            return null;
                        }
                    },
                    operator::snapshot, clock, "BRL");
            return new SaleService(new JdbcSaleRepository(transactions), activeCatalog, authorization,
                    ElectronicPaymentAuthorizer.unavailable(), clock);
        });
        return new TerminalRuntime(authentication, gateway.isPresent(), gateway, operatorService, rotationService, recoveryService,
                renewalService, catalogService, distributionService, heartbeat, saleService);
    }
}
