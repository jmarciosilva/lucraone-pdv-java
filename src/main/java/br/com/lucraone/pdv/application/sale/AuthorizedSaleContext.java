package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.TerminalContext;
import java.util.Objects;

/**
 * Non-secret identity/currency snapshot returned by the sale authorization boundary.
 * The provider must establish explicit local sale permission; ACTIVE terminal status alone is not
 * sufficient. This value is never accepted as a method argument from a sale command.
 */
public record AuthorizedSaleContext(
        TerminalContext terminalContext,
        OperatorSessionData operatorSession,
        String currency
) {
    public AuthorizedSaleContext {
        Objects.requireNonNull(terminalContext, "terminalContext must not be null");
        Objects.requireNonNull(operatorSession, "operatorSession must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
    }
}
