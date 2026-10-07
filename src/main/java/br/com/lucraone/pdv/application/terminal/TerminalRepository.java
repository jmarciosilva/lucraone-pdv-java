package br.com.lucraone.pdv.application.terminal;

import br.com.lucraone.pdv.domain.terminal.Installation;
import br.com.lucraone.pdv.domain.terminal.TerminalProvisioning;
import java.util.Optional;

/**
 * Stores the local installation identity and the terminal provisioning state.
 */
public interface TerminalRepository {

    /**
     * Stores the candidate only when no installation exists yet and returns the stored installation.
     */
    Installation ensureInstallation(Installation candidate);

    Optional<Installation> findInstallation();

    Optional<TerminalProvisioning> findProvisioning();

    /**
     * Atomically creates or replaces the provisioning of the existing installation.
     */
    void saveProvisioning(TerminalProvisioning provisioning);
}
