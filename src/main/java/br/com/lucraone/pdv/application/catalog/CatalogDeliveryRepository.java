package br.com.lucraone.pdv.application.catalog;

import java.time.Instant;
import java.util.List;

/** Local durable storage for transfer checkpoints and immutable active catalog versions. */
public interface CatalogDeliveryRepository {
    void begin(CatalogCacheContext context, CatalogDeliveryManifest manifest);
    int lastStoredPart(CatalogCacheContext context, String deliveryId);
    void storePart(CatalogCacheContext context, String deliveryId, CatalogDeliveryPart part);
    List<byte[]> parts(CatalogCacheContext context, String deliveryId);
    void markReceived(CatalogCacheContext context, String deliveryId);
    void markConfirmed(CatalogCacheContext context, String deliveryId);
    void markApplied(CatalogCacheContext context, CatalogDeliveryManifest manifest,
            List<CatalogProduct> products, Instant appliedAt);
    List<RecoverableCatalogDelivery> recoverable(CatalogCacheContext context);
}
