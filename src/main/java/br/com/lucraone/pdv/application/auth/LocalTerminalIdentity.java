package br.com.lucraone.pdv.application.auth;

import java.util.UUID;

/** Non-secret local terminal references. */
public record LocalTerminalIdentity(UUID installationId, String terminalId, String branchId) {}
