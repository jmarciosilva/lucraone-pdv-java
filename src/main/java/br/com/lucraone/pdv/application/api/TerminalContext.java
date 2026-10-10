package br.com.lucraone.pdv.application.api;

import java.util.Objects;

/**
 * Commercial context the backend attributes to this terminal. All identifiers are opaque external
 * references: the PDV never generates or interprets them.
 */
public record TerminalContext(
        Terminal terminal,
        Tenant tenant,
        Company company,
        Branch branch
) {

    public TerminalContext {
        Objects.requireNonNull(terminal, "terminal must not be null");
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(company, "company must not be null");
        Objects.requireNonNull(branch, "branch must not be null");
    }

    public record Terminal(String id, String name, String status, String installationId) {
        public Terminal {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(installationId, "installationId must not be null");
        }
    }

    public record Tenant(String id, String name) {
        public Tenant {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
        }
    }

    public record Company(String id, String tradeName) {
        public Company {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(tradeName, "tradeName must not be null");
        }
    }

    public record Branch(String id, String name, String code) {
        public Branch {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(code, "code must not be null");
        }
    }
}
