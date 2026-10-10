package br.com.lucraone.pdv.infrastructure.security;

import br.com.lucraone.pdv.application.security.SecretStoreException;
import br.com.lucraone.pdv.application.security.SecretStoreException.Kind;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.concurrent.*;
import static java.lang.foreign.ValueLayout.*;

/** Direct binary libsecret binding (Linux LP64). No shell, argv secret, or application encryption key.
 * Matching locked items fail explicitly. GCancellable cancels operations after ten seconds; native
 * cancellation is cooperative, so callers must always keep these synchronous calls off the UI thread.
 */
final class LibSecretProvider implements NativeSecretProvider {
    private final Bindings bindings;
    LibSecretProvider() {
        try {
            if (ADDRESS.byteSize() != 8) throw new IllegalStateException();
            bindings = new Bindings();
        } catch (RuntimeException | LinkageError e) { throw new SecretStoreException(Kind.UNAVAILABLE); }
    }
    public void save(String key, byte[] secret) {
        try (Call call = new Call(bindings, key)) {
            // Detect locked matches before an overwrite; do not silently create an alternative item.
            call.search();
            MemorySegment bytes = call.arena.allocate(secret.length);
            MemorySegment.copy(secret, 0, bytes, JAVA_BYTE, 0, secret.length);
            MemorySegment value = MemorySegment.NULL;
            try {
                value = bindings.pointer("secret_value_new", bytes, (long) secret.length, call.arena.allocateFrom("application/octet-stream"));
                if (value.address() == 0) throw new SecretStoreException(Kind.WRITE_FAILED);
                int stored = bindings.integer("secret_password_storev_binary_sync", MemorySegment.NULL,
                        call.attributes, call.arena.allocateFrom("default"), call.arena.allocateFrom("LucraOne PDV"),
                        value, call.cancellable, call.error);
                call.check();
                if (stored == 0) throw new SecretStoreException(Kind.WRITE_FAILED);
            } finally {
                bytes.fill((byte) 0);
                if (value.address() != 0) bindings.run("secret_value_unref", value);
            }
        }
    }
    public Optional<byte[]> load(String key) {
        try (Call call = new Call(bindings, key)) {
            List<MemorySegment> items = call.search();
            if (items.isEmpty()) return Optional.empty();
            if (items.size() != 1) throw new SecretStoreException(Kind.READ_FAILED);
            MemorySegment item = items.getFirst();
            int loaded = bindings.integer("secret_item_load_secret_sync", item, call.cancellable, call.error);
            call.check();
            if (loaded == 0) throw new SecretStoreException(Kind.READ_FAILED);
            MemorySegment value = bindings.pointer("secret_item_get_secret", item);
            if (value.address() == 0) throw new SecretStoreException(Kind.UNAVAILABLE);
            try {
                MemorySegment length = call.arena.allocate(JAVA_LONG);
                MemorySegment bytes = bindings.pointer("secret_value_get", value, length);
                long size = length.get(JAVA_LONG, 0);
                if (bytes.address() == 0 || size <= 0 || size > 1024 * 1024) throw new SecretStoreException(Kind.READ_FAILED);
                call.check();
                return Optional.of(bytes.reinterpret(size).toArray(JAVA_BYTE));
            } finally { bindings.run("secret_value_unref", value); }
        }
    }
    public void delete(String key) {
        try (Call call = new Call(bindings, key)) {
            for (MemorySegment item : call.search()) {
                int deleted = bindings.integer("secret_item_delete_sync", item, call.cancellable, call.error);
                call.check();
                if (deleted == 0) throw new SecretStoreException(Kind.DELETE_FAILED);
            }
        }
        // Verify removal without loading secret bytes. A concurrent writer may cause explicit failure.
        try (Call call = new Call(bindings, key)) {
            if (!call.search().isEmpty()) throw new SecretStoreException(Kind.DELETE_FAILED);
        }
    }

    private static final class Call implements AutoCloseable {
        private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "secret-store-cancellation"); thread.setDaemon(true); return thread;
        });
        final Arena arena = Arena.ofConfined();
        final Bindings b;
        final MemorySegment attributes;
        final MemorySegment cancellable;
        final MemorySegment error;
        final ScheduledFuture<?> deadline;
        final Object cancellationLock = new Object();
        private boolean finished;
        private boolean timedOut;
        private MemorySegment list = MemorySegment.NULL;
        private final List<MemorySegment> items = new ArrayList<>();
        Call(Bindings b, String key) {
            this.b = b;
            attributes = b.pointer("g_hash_table_new", b.glib.findOrThrow("g_str_hash"), b.glib.findOrThrow("g_str_equal"));
            cancellable = b.pointer("g_cancellable_new");
            error = arena.allocate(ADDRESS); // zero initialized GError*
            b.integer("g_hash_table_insert", attributes, arena.allocateFrom("application"), arena.allocateFrom("br.com.lucraone.pdv"));
            b.integer("g_hash_table_insert", attributes, arena.allocateFrom("key"), arena.allocateFrom(key));
            deadline = TIMER.schedule(() -> {
                synchronized (cancellationLock) {
                    if (!finished) { timedOut = true; b.run("g_cancellable_cancel", cancellable); }
                }
            }, 10, TimeUnit.SECONDS);
        }
        List<MemorySegment> search() {
            list = b.pointer("secret_service_search_sync", MemorySegment.NULL, MemorySegment.NULL, attributes,
                    1 /* SECRET_SEARCH_ALL; neither unlock nor implicit secret loading */, cancellable, error);
            // Own all returned items even if check() subsequently fails.
            MemorySegment node = list;
            while (node.address() != 0) {
                MemorySegment entry = node.reinterpret(3 * ADDRESS.byteSize());
                items.add(entry.get(ADDRESS, 0));
                node = entry.get(ADDRESS, ADDRESS.byteSize());
            }
            check();
            for (MemorySegment item : items) {
                if (b.integer("secret_item_get_locked", item) != 0) throw new SecretStoreException(Kind.UNAVAILABLE);
            }
            return items;
        }
        void check() {
            MemorySegment nativeError = error.get(ADDRESS, 0);
            if (nativeError.address() != 0) {
                b.run("g_error_free", nativeError); error.set(ADDRESS, 0, MemorySegment.NULL);
                // Do not propagate native text or exception causes: providers may echo secret content.
                throw new SecretStoreException(Kind.UNAVAILABLE);
            }
            synchronized (cancellationLock) { if (timedOut) throw new SecretStoreException(Kind.UNAVAILABLE); }
        }
        public void close() {
            synchronized (cancellationLock) {
                finished = true; deadline.cancel(false);
                b.run("g_object_unref", cancellable);
            }
            MemorySegment nativeError = error.get(ADDRESS, 0);
            if (nativeError.address() != 0) b.run("g_error_free", nativeError);
            for (MemorySegment item : items) b.run("g_object_unref", item);
            if (list.address() != 0) b.run("g_list_free", list);
            b.run("g_hash_table_unref", attributes);
            arena.close();
        }
    }
    private static final class Bindings {
        final SymbolLookup glib = SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global());
        private final SymbolLookup gio = SymbolLookup.libraryLookup("libgio-2.0.so.0", Arena.global());
        private final SymbolLookup objects = SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global());
        private final SymbolLookup secret = SymbolLookup.libraryLookup("libsecret-1.so.0", Arena.global());
        private final Map<String, MethodHandle> calls = new HashMap<>();
        Bindings() {
            bind(glib, "g_hash_table_new", ADDRESS, ADDRESS, ADDRESS);
            bind(glib, "g_hash_table_insert", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
            bind(glib, "g_hash_table_unref", null, ADDRESS);
            bind(glib, "g_list_free", null, ADDRESS);
            bind(glib, "g_error_free", null, ADDRESS);
            bind(gio, "g_cancellable_new", ADDRESS);
            bind(gio, "g_cancellable_cancel", null, ADDRESS);
            bind(objects, "g_object_unref", null, ADDRESS);
            bind(secret, "secret_value_new", ADDRESS, ADDRESS, JAVA_LONG, ADDRESS);
            bind(secret, "secret_value_get", ADDRESS, ADDRESS, ADDRESS);
            bind(secret, "secret_value_unref", null, ADDRESS);
            bind(secret, "secret_password_storev_binary_sync", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
            bind(secret, "secret_service_search_sync", ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS);
            bind(secret, "secret_item_get_locked", JAVA_INT, ADDRESS);
            bind(secret, "secret_item_load_secret_sync", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
            bind(secret, "secret_item_get_secret", ADDRESS, ADDRESS);
            bind(secret, "secret_item_delete_sync", JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
        }
        private void bind(SymbolLookup library, String name, MemoryLayout result, MemoryLayout... args) {
            FunctionDescriptor descriptor = result == null ? FunctionDescriptor.ofVoid(args) : FunctionDescriptor.of(result, args);
            calls.put(name, Linker.nativeLinker().downcallHandle(library.findOrThrow(name), descriptor));
        }
        private Object invoke(String name, Object... args) {
            try { return calls.get(name).invokeWithArguments(args); }
            catch (Throwable e) { throw new SecretStoreException(Kind.UNAVAILABLE); }
        }
        MemorySegment pointer(String name, Object... args) { return (MemorySegment) invoke(name, args); }
        int integer(String name, Object... args) { return (int) invoke(name, args); }
        void run(String name, Object... args) { invoke(name, args); }
    }
}
