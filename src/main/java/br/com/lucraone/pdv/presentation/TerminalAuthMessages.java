package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.auth.TerminalAuthState;

/** User-facing feedback contains no provider names or backend error bodies. */
final class TerminalAuthMessages {
    private TerminalAuthMessages() {}
    static String describe(TerminalAuthState state) {
        return switch (state) {
            case NOT_PROVISIONED -> "Terminal não provisionado.";
            case PROVISIONED_NO_CREDENTIAL -> "Terminal sem credencial. Faça um novo pairing presencial.";
            case CREDENTIAL_PRESENT_UNVERIFIED -> "Credencial encontrada; a conexão do terminal ainda não foi validada.";
            case AUTHENTICATED -> "Terminal conectado. Online.";
            case OFFLINE_LIMITED -> "Offline. Identidade do terminal previamente validada; este estado não autoriza vendas.";
            case REAUTHENTICATION_REQUIRED -> "Reautenticação necessária. Faça um novo pairing presencial.";
            case SECURE_STORE_UNAVAILABLE -> "Armazenamento seguro indisponível. Não é possível conectar o terminal.";
            case CONTEXT_MISMATCH -> "Os dados do terminal divergem da instalação local. Contate o suporte.";
            case LOCAL_STATE_UNAVAILABLE -> "Não foi possível acessar o estado local do terminal.";
            case VALIDATION_FAILED -> "Não foi possível validar o terminal. Tente verificar a conexão novamente.";
            case INCONSISTENT_STATE -> "A autenticação local está inconsistente. Remova a autenticação local antes de reconectar, ou contate o suporte.";
            case BACKEND_NOT_CONFIGURED -> "Configure o endereço da API antes de conectar o terminal.";
        };
    }
}
