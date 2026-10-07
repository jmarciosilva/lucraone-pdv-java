package br.com.lucraone.pdv.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.lucraone.pdv.application.security.SecretProtectionException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

class WindowsDpapiSecretProtectorTest {

    private static final byte[] PLAINTEXT = "valor-de-teste-nao-real".getBytes(StandardCharsets.UTF_8);

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void protectsAndRecoversTheSameBytes() {
        WindowsDpapiSecretProtector protector = new WindowsDpapiSecretProtector();

        byte[] protectedData = protector.protect(PLAINTEXT);

        assertArrayEquals(PLAINTEXT, protector.unprotect(protectedData));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void protectedDataDoesNotContainThePlaintext() {
        WindowsDpapiSecretProtector protector = new WindowsDpapiSecretProtector();

        byte[] protectedData = protector.protect(PLAINTEXT);

        assertFalse(contains(protectedData, PLAINTEXT));
        assertFalse(Arrays.equals(protectedData, protector.protect(PLAINTEXT)));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsEmptyInput() {
        WindowsDpapiSecretProtector protector = new WindowsDpapiSecretProtector();

        assertThrows(IllegalArgumentException.class, () -> protector.protect(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> protector.unprotect(new byte[0]));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void failsClearlyWhenProtectedDataWasTampered() {
        WindowsDpapiSecretProtector protector = new WindowsDpapiSecretProtector();
        byte[] protectedData = protector.protect(PLAINTEXT);
        protectedData[protectedData.length - 1] ^= 0x01;

        assertThrows(SecretProtectionException.class, () -> protector.unprotect(protectedData));
    }

    @Test
    void failsClearlyOutsideWindows() {
        assertThrows(SecretProtectionException.class, () -> new WindowsDpapiSecretProtector("Linux"));
    }

    private static boolean contains(byte[] data, byte[] fragment) {
        for (int start = 0; start <= data.length - fragment.length; start++) {
            if (Arrays.equals(data, start, start + fragment.length, fragment, 0, fragment.length)) {
                return true;
            }
        }
        return false;
    }
}
