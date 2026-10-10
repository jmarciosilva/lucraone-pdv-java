package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;

/** DPAPI ciphertext only, with atomic replacement. No plaintext is written to a file. */
public final class WindowsSecretStore implements SecretStore {
    private final Path directory;
    private final SecretProtector protector;
    public WindowsSecretStore(Path directory, SecretProtector protector) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector);
    }
    private Path file(String key) {
        try {
            return directory.resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8))) + ".bin");
        } catch (java.security.NoSuchAlgorithmException e) { throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE); }
    }
    public synchronized void save(String key, byte[] secret) {
        SecretStoreValidation.key(key); SecretStoreValidation.secret(secret);
        byte[] input = secret.clone(); byte[] encrypted = null; Path temporary = null;
        try {
            encrypted = protector.protect(input);
            if (encrypted == null || encrypted.length == 0) throw new SecretStoreException(SecretStoreException.Kind.WRITE_FAILED);
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new SecretStoreException(SecretStoreException.Kind.WRITE_FAILED);
            temporary = Files.createTempFile(directory, "protected-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(encrypted);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (SecretProtectionException e) { throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE); }
        catch (Exception e) { throw new SecretStoreException(SecretStoreException.Kind.WRITE_FAILED); }
        finally {
            Arrays.fill(input, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) { /* ciphertext only */ }
        }
    }
    public synchronized Optional<byte[]> load(String key) {
        SecretStoreValidation.key(key); byte[] encrypted = null;
        try {
            try (var channel = Files.newByteChannel(file(key), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                long size = channel.size();
                if (size <= 0 || size > 2 * 1024 * 1024) throw new SecretStoreException(SecretStoreException.Kind.READ_FAILED);
                encrypted = new byte[(int) size]; ByteBuffer buffer = ByteBuffer.wrap(encrypted);
                while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new SecretStoreException(SecretStoreException.Kind.READ_FAILED);
            }
            byte[] result = protector.unprotect(encrypted);
            SecretStoreValidation.secret(result);
            return Optional.of(result);
        } catch (NoSuchFileException e) { return Optional.empty(); }
        catch (SecretProtectionException e) { throw new SecretStoreException(SecretStoreException.Kind.UNAVAILABLE); }
        catch (Exception e) { throw new SecretStoreException(SecretStoreException.Kind.READ_FAILED); }
        finally { if (encrypted != null) Arrays.fill(encrypted, (byte) 0); }
    }
    public synchronized void delete(String key) {
        SecretStoreValidation.key(key);
        try { Files.deleteIfExists(file(key)); }
        catch (Exception e) { throw new SecretStoreException(SecretStoreException.Kind.DELETE_FAILED); }
    }
    public String toString() { return "WindowsSecretStore[protected]"; }
}
