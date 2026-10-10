package br.com.lucraone.pdv.application.auth;

import java.util.Optional;

public interface TerminalValidationRepository {
    Optional<TerminalValidation> load();
    void save(TerminalValidation validation);
    void invalidate();
}
