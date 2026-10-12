package br.com.lucraone.pdv.application.catalog;

public record CatalogSyncReport(int discovered, int applied, int recoveredAcknowledgements) { }
