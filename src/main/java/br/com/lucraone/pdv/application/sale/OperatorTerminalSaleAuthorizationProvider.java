package br.com.lucraone.pdv.application.sale;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.application.operator.OperatorAuthSnapshot;
import br.com.lucraone.pdv.application.operator.OperatorAuthState;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Operational sale authorization derived only from state the PDV already holds: a terminal context
 * verified online, a non-expired machine credential and a live, authenticated operator session.
 *
 * <p>No backend ability is consulted (the contract does not expose one) and no identity is simulated.
 * Authorization is re-evaluated on every call, so a revocation known to the terminal blocks the next
 * sale immediately: when the authorization monitor observes a denied, offline or failed terminal
 * validation it suspends the operator session, after which the operator snapshot is no longer
 * {@link OperatorAuthState#AUTHENTICATED} and this provider denies.
 *
 * <p>The machine-credential expiry is checked here because {@link SaleService} never sees the
 * credential; {@code SaleService} still independently re-checks terminal status, operator session
 * status and session expiry before persisting a sale.
 */
public final class OperatorTerminalSaleAuthorizationProvider implements SaleAuthorizationProvider {

    private final Supplier<Optional<TerminalContext>> verifiedContext;
    private final Supplier<MachineCredential> machineCredential;
    private final Supplier<OperatorAuthSnapshot> operatorSnapshot;
    private final Clock clock;
    private final String currency;

    public OperatorTerminalSaleAuthorizationProvider(
            Supplier<Optional<TerminalContext>> verifiedContext,
            Supplier<MachineCredential> machineCredential,
            Supplier<OperatorAuthSnapshot> operatorSnapshot,
            Clock clock,
            String currency) {
        this.verifiedContext = Objects.requireNonNull(verifiedContext, "verifiedContext must not be null");
        this.machineCredential = Objects.requireNonNull(machineCredential, "machineCredential must not be null");
        this.operatorSnapshot = Objects.requireNonNull(operatorSnapshot, "operatorSnapshot must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
    }

    @Override
    public AuthorizedSaleContext requireAuthorizedContext(SaleAuthorizationAction action) {
        Objects.requireNonNull(action, "action must not be null");

        TerminalContext terminal = verifiedContext.get()
                .orElseThrow(() -> new IllegalStateException(
                        "No terminal context has been verified online for sale operations."));

        MachineCredential credential = machineCredential.get();
        if (credential == null) {
            throw new IllegalStateException("A terminal machine credential is required for sale operations.");
        }
        if (credential.expiredAt(clock.instant())) {
            throw new IllegalStateException("The terminal machine credential has expired for sale operations.");
        }

        OperatorAuthSnapshot operator = operatorSnapshot.get();
        if (operator == null || operator.state() != OperatorAuthState.AUTHENTICATED) {
            throw new IllegalStateException("An authenticated operator session is required for sale operations.");
        }
        OperatorSessionData session = operator.session()
                .orElseThrow(() -> new IllegalStateException(
                        "The authenticated operator session carries no session data."));

        return new AuthorizedSaleContext(terminal, session, currency);
    }
}
