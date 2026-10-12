package br.com.lucraone.pdv.application.catalog;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.PdvBackendGateway;
import br.com.lucraone.pdv.application.api.PdvApiException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Resumable, machine-authenticated catalog download and atomic activation workflow. */
public final class CatalogDistributionService {
    private final PdvBackendGateway gateway;
    private final CatalogDeliveryRepository repository;
    private final CatalogSnapshotDecoder decoder;
    private final Clock clock;
    private final Executor worker;
    private final AtomicBoolean running = new AtomicBoolean();

    public CatalogDistributionService(PdvBackendGateway gateway, CatalogDeliveryRepository repository,
            CatalogSnapshotDecoder decoder, Clock clock, Executor worker) {
        this.gateway = Objects.requireNonNull(gateway); this.repository = Objects.requireNonNull(repository);
        this.decoder = Objects.requireNonNull(decoder); this.clock = Objects.requireNonNull(clock);
        this.worker = Objects.requireNonNull(worker);
    }

    /** One coalesced pass. A caller cannot start overlapping downloads for the same runtime instance. */
    public CompletableFuture<CatalogSyncReport> synchronize(MachineCredential credential, CatalogCacheContext context) {
        Objects.requireNonNull(credential); Objects.requireNonNull(context);
        if (!running.compareAndSet(false, true)) return CompletableFuture.completedFuture(new CatalogSyncReport(0, 0, 0));
        CompletableFuture<CatalogSyncReport> result = recover(credential, context)
                .thenCompose(recovered -> gateway.pendingCatalogDeliveries(credential)
                        .thenCompose(deliveries -> processDeliveries(credential, context, deliveries, 0)
                                .thenApply(applied -> new CatalogSyncReport(deliveries.size(), applied, recovered))));
        return result.whenComplete((value, error) -> running.set(false));
    }

    private CompletableFuture<Integer> recover(MachineCredential credential, CatalogCacheContext context) {
        return CompletableFuture.supplyAsync(() -> repository.recoverable(context), worker)
                .thenCompose(states -> recoverAt(credential, context, states, 0, 0));
    }

    private CompletableFuture<Integer> recoverAt(MachineCredential credential, CatalogCacheContext context,
            List<RecoverableCatalogDelivery> states, int index, int count) {
        if (index >= states.size()) return CompletableFuture.completedFuture(count);
        RecoverableCatalogDelivery state = states.get(index);
        CompletableFuture<Void> recovered;
        if (state.state().equals("APPLIED")) {
            recovered = gateway.confirmCatalogDeliveryApplied(credential, state.manifest().deliveryId())
                    .thenRunAsync(() -> repository.markConfirmed(context, state.manifest().deliveryId()), worker);
        } else {
            recovered = gateway.confirmCatalogDeliveryReceived(credential, state.manifest().deliveryId())
                    .thenApplyAsync(ignored -> validateAndDecode(state.manifest(), repository.parts(context, state.manifest().deliveryId())), worker)
                    .thenAcceptAsync(products -> repository.markApplied(context, state.manifest(), products, clock.instant()), worker)
                    .thenCompose(ignored -> gateway.confirmCatalogDeliveryApplied(credential, state.manifest().deliveryId()))
                    .thenRunAsync(() -> repository.markConfirmed(context, state.manifest().deliveryId()), worker);
        }
        return recovered.thenCompose(ignored -> recoverAt(credential, context, states, index + 1, count + 1));
    }

    private CompletableFuture<Integer> processDeliveries(MachineCredential credential, CatalogCacheContext context,
            List<CatalogDelivery> deliveries, int index) {
        if (index >= deliveries.size()) return CompletableFuture.completedFuture(0);
        CatalogDelivery delivery = deliveries.get(index);
        CompletableFuture<Integer> current = gateway.catalogDeliveryManifest(credential, delivery.deliveryId())
                .thenCompose(manifest -> prepareAndTransfer(credential, context, delivery, manifest));
        return current.thenCompose(applied -> processDeliveries(credential, context, deliveries, index + 1)
                .thenApply(rest -> applied + rest));
    }

    private CompletableFuture<Integer> prepareAndTransfer(MachineCredential credential, CatalogCacheContext context,
            CatalogDelivery delivery, CatalogDeliveryManifest manifest) {
        if (!delivery.deliveryId().equals(manifest.deliveryId()) || !delivery.publicationId().equals(manifest.publicationId())
                || delivery.version() != manifest.version()) return CompletableFuture.failedFuture(new IllegalStateException("Delivery and manifest identity do not match."));
        if (manifest.schemaVersion() != 1) {
            return gateway.failCatalogDelivery(credential, delivery.deliveryId(), "UNSUPPORTED_SCHEMA")
                    .thenApply(ignored -> 0);
        }
        return CompletableFuture.runAsync(() -> repository.begin(context, manifest), worker)
                .thenCompose(ignored -> transferParts(credential, context, manifest))
                .thenApplyAsync(ignored -> validateAndDecode(manifest, repository.parts(context, manifest.deliveryId())), worker)
                .thenCompose(products -> CompletableFuture.runAsync(() -> repository.markReceived(context, manifest.deliveryId()), worker)
                        .thenCompose(ignored -> gateway.confirmCatalogDeliveryReceived(credential, manifest.deliveryId()))
                        .thenRunAsync(() -> repository.markApplied(context, manifest, products, clock.instant()), worker)
                        .thenCompose(ignored -> gateway.confirmCatalogDeliveryApplied(credential, manifest.deliveryId()))
                        .thenRunAsync(() -> repository.markConfirmed(context, manifest.deliveryId()), worker)
                        .thenApply(ignored -> 1))
                .exceptionallyCompose(error -> {
                    Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    if (cause instanceof PdvApiException) return CompletableFuture.failedFuture(cause);
                    String code = cause instanceof IllegalArgumentException ? "CHECKSUM_MISMATCH" : "STORAGE_ERROR";
                    return gateway.failCatalogDelivery(credential, delivery.deliveryId(), code)
                            .handle((ignored, reportError) -> { throw new java.util.concurrent.CompletionException(cause); });
                });
    }

    private CompletableFuture<Void> transferParts(MachineCredential credential, CatalogCacheContext context,
            CatalogDeliveryManifest manifest) {
        int lastStored = repository.lastStoredPart(context, manifest.deliveryId());
        if (manifest.resumeFromPart() > lastStored + 1) return CompletableFuture.failedFuture(new IllegalStateException("Server checkpoint is ahead of the local verified parts."));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        if (lastStored >= manifest.resumeFromPart() && lastStored >= 0) {
            chain = gateway.reportCatalogDeliveryProgress(credential, manifest.deliveryId(), lastStored);
        }
        int start = Math.max(manifest.resumeFromPart(), lastStored + 1);
        for (int partIndex = start; partIndex < manifest.partCount(); partIndex++) {
            int number = partIndex;
            chain = chain.thenCompose(ignored -> gateway.catalogDeliveryPart(credential, manifest.deliveryId(), number)
                    .thenApplyAsync(part -> verifyAndStore(context, manifest, number, part), worker)
                    .thenCompose(part -> gateway.reportCatalogDeliveryProgress(credential, manifest.deliveryId(), part.part())));
        }
        return chain;
    }

    private CatalogDeliveryPart verifyAndStore(CatalogCacheContext context, CatalogDeliveryManifest manifest,
            int expectedPart, CatalogDeliveryPart part) {
        if (part.part() != expectedPart || part.partCount() != manifest.partCount()
                || !sha256(part.content()).equalsIgnoreCase(part.checksum())) throw new IllegalArgumentException("Catalog part checksum or sequence is invalid.");
        repository.storePart(context, manifest.deliveryId(), part);
        return part;
    }

    private List<CatalogProduct> validateAndDecode(CatalogDeliveryManifest manifest, List<byte[]> parts) {
        if (parts.size() != manifest.partCount()) throw new IllegalStateException("Local part count is incomplete.");
        MessageDigest digest = sha256Digest();
        parts.forEach(digest::update);
        if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(manifest.checksum())) throw new IllegalArgumentException("Catalog snapshot checksum does not match the manifest.");
        List<CatalogProduct> products = decoder.decode(parts);
        long prices = products.stream().mapToLong(p -> p.salePrices().size()).sum();
        long packages = products.stream().mapToLong(p -> p.packages().size()).sum();
        if (products.size() != manifest.productCount() || prices != manifest.priceCount() || packages != manifest.packageCount())
            throw new IllegalArgumentException("Catalog snapshot record counts do not match the manifest.");
        return products;
    }

    private static String sha256(byte[] content) { return HexFormat.of().formatHex(sha256Digest().digest(content)); }
    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable.", exception); }
    }
}
