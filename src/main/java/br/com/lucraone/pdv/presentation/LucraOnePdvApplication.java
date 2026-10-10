package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.ApplicationMetadata;
import br.com.lucraone.pdv.application.auth.TerminalAuthSnapshot;
import br.com.lucraone.pdv.infrastructure.auth.TerminalRuntime;
import br.com.lucraone.pdv.infrastructure.auth.TerminalRuntimeFactory;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Minimal terminal lifecycle UI. Every database, native store and HTTP operation runs on the worker. */
public class LucraOnePdvApplication extends Application {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "terminal-auth-worker");
        // Finish an in-flight write and resource cleanup when the window closes.
        thread.setDaemon(false);
        return thread;
    });
    private final Label message = new Label("Preparando terminal…");
    private final Label configuration = new Label();
    private final PasswordField pairingCode = new PasswordField();
    private final Button pair = new Button("Conectar terminal");
    private final Button validate = new Button("Verificar conexão");
    private final Button forget = new Button("Esquecer autenticação local");
    private final PauseTransition expiry = new PauseTransition();
    private volatile TerminalRuntime runtime;
    private boolean stopped;
    private boolean secureStorageUnavailable;

    @Override public void start(Stage stage) {
        Label title = new Label(ApplicationMetadata.name());
        title.getStyleClass().add("application-title");
        message.setWrapText(true);
        message.getStyleClass().add("application-message");
        configuration.setWrapText(true);
        pairingCode.setPromptText("Código de conexão presencial");
        pairingCode.setMaxWidth(320);
        var operator = new Label("Acesso do operador ainda indisponível.");
        VBox root = new VBox(12, title, message, configuration, pairingCode, pair, validate, forget, operator);
        root.getStyleClass().add("application-root");
        root.setAlignment(Pos.CENTER);
        Scene scene = new Scene(root, 1000, 700);
        scene.getStylesheets().add(getClass().getResource("/br/com/lucraone/pdv/presentation/application.css").toExternalForm());
        stage.setTitle(ApplicationMetadata.name());
        stage.setScene(scene);
        stage.show();
        setBusy(true);
        pair.setOnAction(event -> {
            String code = pairingCode.getText();
            pairingCode.clear();
            if (code.isBlank()) { message.setText("Informe um código de conexão."); return; }
            perform(() -> runtime.authentication().pair(code));
        });
        validate.setOnAction(event -> perform(() -> runtime.authentication().restore()));
        forget.setOnAction(event -> perform(() -> runtime.authentication().forget()));
        CompletableFuture.supplyAsync(() -> {
            runtime = TerminalRuntimeFactory.create(worker);
            return runtime;
        }, worker).thenCompose(value -> value.authentication().restore()).whenComplete(this::complete);
    }

    private void perform(Supplier<CompletableFuture<TerminalAuthSnapshot>> operation) {
        expiry.stop();
        setBusy(true);
        operation.get().whenComplete(this::complete);
    }

    private void complete(TerminalAuthSnapshot snapshot, Throwable error) {
        Platform.runLater(() -> {
            if (stopped) return;
            expiry.stop();
            if (error != null) {
                message.setText("Não foi possível preparar o terminal. Verifique a configuração e o armazenamento local.");
                setBusy(false);
                return;
            }
            secureStorageUnavailable = snapshot.state()
                    == br.com.lucraone.pdv.application.auth.TerminalAuthState.SECURE_STORE_UNAVAILABLE;
            message.setText(TerminalAuthMessages.describe(snapshot.state()));
            configuration.setText(runtime.backendConfigured() ? "" : "Configure o endereço do servidor para conectar o terminal.");
            setBusy(false);
            if (snapshot.state() == br.com.lucraone.pdv.application.auth.TerminalAuthState.AUTHENTICATED
                    || snapshot.state() == br.com.lucraone.pdv.application.auth.TerminalAuthState.OFFLINE_LIMITED) {
            snapshot.expiresAt().ifPresent(instant -> {
                // One expiration event, not network polling. Re-evaluation at expiry makes no HTTP call.
                expiry.setDuration(Duration.millis(Math.max(1, java.time.Duration.between(Instant.now(), instant).toMillis() + 1)));
                expiry.setOnFinished(event -> perform(() -> runtime.authentication().restore()));
                expiry.playFromStart();
            });
            }
        });
    }

    private void setBusy(boolean busy) {
        pair.setDisable(busy || runtime == null || !runtime.backendConfigured() || secureStorageUnavailable);
        validate.setDisable(busy || runtime == null);
        forget.setDisable(busy || runtime == null);
        pairingCode.setDisable(busy || runtime == null || !runtime.backendConfigured() || secureStorageUnavailable);
    }

    @Override public void stop() {
        stopped = true;
        pairingCode.clear();
        expiry.stop();
        // Close may wait for the HTTP client: never do that on the JavaFX thread.
        worker.execute(() -> { if (runtime != null) runtime.close(); });
        worker.shutdown();
    }

    public static void main(String[] args) { launch(args); }
}
