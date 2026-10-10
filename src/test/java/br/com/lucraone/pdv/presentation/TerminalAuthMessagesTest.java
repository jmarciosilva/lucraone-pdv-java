package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.auth.TerminalAuthState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerminalAuthMessagesTest {
    @Test void everyStateHasSanitizedUserFeedback() {
        for (var state : TerminalAuthState.values()) {
            String message = TerminalAuthMessages.describe(state);
            assertFalse(message.isBlank());
            for (String technical : new String[]{"DPAPI", "libsecret", "401", "403", "SecretStoreException"}) {
                assertFalse(message.contains(technical));
            }
        }
    }
    @Test void offlineDoesNotOfferCommercialAuthorization() {
        assertTrue(TerminalAuthMessages.describe(TerminalAuthState.OFFLINE_LIMITED).contains("Offline"));
        assertTrue(TerminalAuthMessages.describe(TerminalAuthState.OFFLINE_LIMITED).contains("vendas"));
    }
}
