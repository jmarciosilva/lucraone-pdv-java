package br.com.lucraone.pdv.application.auth;

import br.com.lucraone.pdv.application.api.TerminalContext;
import br.com.lucraone.pdv.domain.terminal.TerminalProvisioning;
import java.time.Instant;
import java.util.UUID;

final class TerminalContextIntegrity {
    private TerminalContextIntegrity() {}
    static TerminalProvisioning provisioning(TerminalContext context, UUID installationId, Instant now) {
        if (!installationId.toString().equals(context.terminal().installationId())) throw new IllegalArgumentException();
        TerminalProvisioning refs = new TerminalProvisioning(context.terminal().id(),context.branch().id(),now);
        if (!refs.terminalId().equals(context.terminal().id()) || !refs.branchId().equals(context.branch().id())) throw new IllegalArgumentException();
        return refs;
    }
    static boolean matches(TerminalContext context,UUID installationId,TerminalProvisioning refs) {
        return installationId.toString().equals(context.terminal().installationId())
                && refs.terminalId().equals(context.terminal().id()) && refs.branchId().equals(context.branch().id());
    }
}
