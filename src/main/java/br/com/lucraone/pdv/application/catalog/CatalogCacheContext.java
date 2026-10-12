package br.com.lucraone.pdv.application.catalog;

import br.com.lucraone.pdv.application.api.TerminalContext;
import java.util.Objects;

/** Non-secret identity key that keeps cached rows inside the authorized terminal/company context. */
public record CatalogCacheContext(String terminalId, String tenantId, String companyId) {
    public CatalogCacheContext {
        Objects.requireNonNull(terminalId); Objects.requireNonNull(tenantId); Objects.requireNonNull(companyId);
        if (terminalId.isBlank() || tenantId.isBlank() || companyId.isBlank()) throw new IllegalArgumentException("cache context ids must be nonblank");
    }
    public static CatalogCacheContext from(TerminalContext context) {
        Objects.requireNonNull(context);
        return new CatalogCacheContext(context.terminal().id(), context.tenant().id(), context.company().id());
    }
}
