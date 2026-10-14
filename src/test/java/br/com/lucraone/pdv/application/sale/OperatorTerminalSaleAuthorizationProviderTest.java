package br.com.lucraone.pdv.application.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorSessionData;
import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.application.operator.OperatorAuthSnapshot;
import br.com.lucraone.pdv.application.operator.OperatorAuthState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OperatorTerminalSaleAuthorizationProviderTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void authorizesWhenTerminalCredentialAndOperatorSessionAreValid() {
        AuthorizedSaleContext context = provider(
                Optional.of(terminal("active")), validCredential(), authenticated())
                .requireAuthorizedContext(SaleAuthorizationAction.CREATE_SALE);

        assertEquals("terminal-1", context.terminalContext().terminal().id());
        assertEquals("operator-1", context.operatorSession().operatorId());
        assertEquals("BRL", context.currency());
    }

    @Test
    void deniesWhenNoTerminalContextWasVerified() {
        assertThrows(IllegalStateException.class, () -> provider(
                Optional.empty(), validCredential(), authenticated())
                .requireAuthorizedContext(SaleAuthorizationAction.CREATE_SALE));
    }

    @Test
    void deniesWhenMachineCredentialIsMissing() {
        assertThrows(IllegalStateException.class, () -> provider(
                Optional.of(terminal("active")), null, authenticated())
                .requireAuthorizedContext(SaleAuthorizationAction.CREATE_SALE));
    }

    @Test
    void deniesWhenMachineCredentialHasExpired() {
        MachineCredential expired = new MachineCredential("Bearer", "token", NOW.minusSeconds(1));
        assertThrows(IllegalStateException.class, () -> provider(
                Optional.of(terminal("active")), expired, authenticated())
                .requireAuthorizedContext(SaleAuthorizationAction.CREATE_SALE));
    }

    @Test
    void deniesWhenOperatorIsNotAuthenticated() {
        assertThrows(IllegalStateException.class, () -> provider(
                Optional.of(terminal("active")), validCredential(),
                OperatorAuthSnapshot.of(OperatorAuthState.NOT_AUTHENTICATED))
                .requireAuthorizedContext(SaleAuthorizationAction.CREATE_SALE));
    }

    @Test
    void deniesWhenAuthenticatedSnapshotCarriesNoSessionData() {
        OperatorAuthSnapshot noSession =
                new OperatorAuthSnapshot(OperatorAuthState.AUTHENTICATED, Optional.empty(), Optional.empty());
        assertThrows(IllegalStateException.class, () -> provider(
                Optional.of(terminal("active")), validCredential(), noSession)
                .requireAuthorizedContext(SaleAuthorizationAction.CREATE_SALE));
    }

    @Test
    void rejectsNullAction() {
        assertThrows(NullPointerException.class, () -> provider(
                Optional.of(terminal("active")), validCredential(), authenticated())
                .requireAuthorizedContext(null));
    }

    @Test
    void reevaluatesAuthorizationOnEveryCallSoSuspensionBlocksImmediately() {
        AtomicReference<OperatorAuthSnapshot> operator = new AtomicReference<>(authenticated());
        OperatorTerminalSaleAuthorizationProvider provider = new OperatorTerminalSaleAuthorizationProvider(
                () -> Optional.of(terminal("active")), this::validCredential, operator::get, CLOCK, "BRL");

        provider.requireAuthorizedContext(SaleAuthorizationAction.CREATE_SALE);
        // The authorization monitor suspends the operator session on a known revocation.
        operator.set(OperatorAuthSnapshot.of(OperatorAuthState.SESSION_INVALID));
        assertThrows(IllegalStateException.class,
                () -> provider.requireAuthorizedContext(SaleAuthorizationAction.CREATE_SALE));
    }

    private OperatorTerminalSaleAuthorizationProvider provider(Optional<TerminalContext> context,
            MachineCredential credential, OperatorAuthSnapshot operator) {
        return new OperatorTerminalSaleAuthorizationProvider(
                () -> context, () -> credential, () -> operator, CLOCK, "BRL");
    }

    private MachineCredential validCredential() {
        return new MachineCredential("Bearer", "token", NOW.plusSeconds(600));
    }

    private OperatorAuthSnapshot authenticated() {
        return new OperatorAuthSnapshot(OperatorAuthState.AUTHENTICATED,
                Optional.of(new OperatorSessionData("session-1", "active", NOW.plusSeconds(600),
                        "operator-1", "Operador", "operador", "operador@example.test")),
                Optional.empty());
    }

    private TerminalContext terminal(String status) {
        return new TerminalContext(
                new TerminalContext.Terminal("terminal-1", "Caixa", status, "install-1"),
                new TerminalContext.Tenant("tenant-1", "Tenant"),
                new TerminalContext.Company("company-1", "Company"),
                new TerminalContext.Branch("branch-1", "Branch", "01"));
    }
}
