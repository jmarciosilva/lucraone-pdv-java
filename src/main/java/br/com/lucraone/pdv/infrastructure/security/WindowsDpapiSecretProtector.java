package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.SecretProtectionException;
import br.com.lucraone.pdv.application.security.SecretProtector;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Locale;
import java.util.Objects;

/**
 * Protects secrets with Windows DPAPI scoped to the current Windows user, called through the JDK Foreign
 * Function API. The application holds no key material; Windows derives it from the user profile.
 */
public final class WindowsDpapiSecretProtector implements SecretProtector {

    public WindowsDpapiSecretProtector() {
        this(System.getProperty("os.name", ""));
    }

    WindowsDpapiSecretProtector(String operatingSystemName) {
        if (!operatingSystemName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new SecretProtectionException("A proteção de segredos via DPAPI exige Windows.");
        }
    }

    @Override
    public byte[] protect(byte[] plaintext) {
        requireNotEmpty(plaintext, "plaintext");
        return Dpapi.transform(Dpapi.CRYPT_PROTECT_DATA, "CryptProtectData", plaintext);
    }

    @Override
    public byte[] unprotect(byte[] protectedData) {
        requireNotEmpty(protectedData, "protectedData");
        return Dpapi.transform(Dpapi.CRYPT_UNPROTECT_DATA, "CryptUnprotectData", protectedData);
    }

    private static void requireNotEmpty(byte[] data, String name) {
        Objects.requireNonNull(data, name + " must not be null");
        if (data.length == 0) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }

    /**
     * Native bindings, loaded only after the platform check succeeded.
     */
    private static final class Dpapi {

        private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

        // typedef struct { DWORD cbData; BYTE *pbData; } DATA_BLOB;
        private static final StructLayout DATA_BLOB = MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("cbData"),
                MemoryLayout.paddingLayout(4),
                ValueLayout.ADDRESS.withName("pbData")
        );
        private static final VarHandle BLOB_SIZE = DATA_BLOB.varHandle(PathElement.groupElement("cbData"));
        private static final VarHandle BLOB_DATA = DATA_BLOB.varHandle(PathElement.groupElement("pbData"));

        private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
        private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(PathElement.groupElement("GetLastError"));

        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup CRYPT32 = SymbolLookup.libraryLookup("crypt32", Arena.global());
        private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

        // CryptProtectData and CryptUnprotectData share the shape (in, description, entropy, reserved, prompt, flags, out).
        private static final FunctionDescriptor TRANSFORM = FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS
        );

        static final MethodHandle CRYPT_PROTECT_DATA = transformHandle("CryptProtectData");
        static final MethodHandle CRYPT_UNPROTECT_DATA = transformHandle("CryptUnprotectData");
        private static final MethodHandle LOCAL_FREE = LINKER.downcallHandle(
                KERNEL32.findOrThrow("LocalFree"),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
        );

        private static MethodHandle transformHandle(String name) {
            return LINKER.downcallHandle(CRYPT32.findOrThrow(name), TRANSFORM, Linker.Option.captureCallState("GetLastError"));
        }

        static byte[] transform(MethodHandle function, String functionName, byte[] input) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment inputData = arena.allocate(input.length);
                MemorySegment.copy(input, 0, inputData, ValueLayout.JAVA_BYTE, 0, input.length);
                MemorySegment inputBlob = arena.allocate(DATA_BLOB);
                BLOB_SIZE.set(inputBlob, 0L, input.length);
                BLOB_DATA.set(inputBlob, 0L, inputData);
                MemorySegment outputBlob = arena.allocate(DATA_BLOB);
                MemorySegment callState = arena.allocate(CALL_STATE);

                int succeeded;
                try {
                    succeeded = (int) function.invokeExact(
                            callState,
                            inputBlob,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            MemorySegment.NULL,
                            CRYPTPROTECT_UI_FORBIDDEN,
                            outputBlob
                    );
                } catch (RuntimeException | Error exception) {
                    throw exception;
                } catch (Throwable exception) {
                    throw new SecretProtectionException("Falha ao chamar " + functionName + ".", exception);
                } finally {
                    inputData.fill((byte) 0);
                }

                if (succeeded == 0) {
                    int lastError = (int) LAST_ERROR.get(callState, 0L);
                    throw new SecretProtectionException(
                            functionName + " falhou (código Windows " + String.format("0x%08X", lastError) + ")."
                    );
                }
                return takeOutput(outputBlob);
            }
        }

        private static byte[] takeOutput(MemorySegment outputBlob) {
            int size = (int) BLOB_SIZE.get(outputBlob, 0L);
            MemorySegment data = ((MemorySegment) BLOB_DATA.get(outputBlob, 0L)).reinterpret(size);
            try {
                return data.toArray(ValueLayout.JAVA_BYTE);
            } finally {
                data.fill((byte) 0);
                localFree(data);
            }
        }

        private static void localFree(MemorySegment data) {
            try {
                MemorySegment ignored = (MemorySegment) LOCAL_FREE.invokeExact(data);
            } catch (RuntimeException | Error exception) {
                throw exception;
            } catch (Throwable exception) {
                throw new SecretProtectionException("Falha ao liberar a memória do DPAPI.", exception);
            }
        }
    }
}
