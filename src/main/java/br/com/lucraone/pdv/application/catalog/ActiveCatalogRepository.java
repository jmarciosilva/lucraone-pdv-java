package br.com.lucraone.pdv.application.catalog;

public interface ActiveCatalogRepository {
    CatalogPage search(CatalogCacheContext context, String search, int perPage, String cursor);
    BarcodeResolution resolveBarcode(CatalogCacheContext context, String barcode);
}
