package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.ApplicationMetadata;
import br.com.lucraone.pdv.application.auth.TerminalAuthSnapshot;
import br.com.lucraone.pdv.application.auth.TerminalAuthState;
import br.com.lucraone.pdv.application.operator.OperatorAuthSnapshot;
import br.com.lucraone.pdv.application.operator.OperatorAuthState;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorLoginCommand;
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
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Three-screen terminal lifecycle UI:
 *  1. Setup / pairing — shown when no authenticated terminal credential exists.
 *  2. Operator login — shown when the terminal is authenticated but no operator session is open.
 *  3. Cashier — shown when an operator session is active.
 *
 * Every database, native store and HTTP operation runs on the worker; only Platform.runLater
 * touches the UI thread.
 */
public class LucraOnePdvApplication extends Application {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "terminal-auth-worker");
        thread.setDaemon(false);
        return thread;
    });

    // ── Setup / pairing ─────────────────────────────────────────────────────
    private final Label setupMessage = new Label("Preparando terminal…");
    private final Label setupConfig  = new Label();
    private final PasswordField pairingCodeField = new PasswordField();
    private final Button pairButton     = new Button("Conectar terminal");
    private final Button validateButton = new Button("Verificar conexão");
    private final Button forgetButton   = new Button("Esquecer autenticação local");
    private final VBox setupScreen;

    // ── Operator login ──────────────────────────────────────────────────────
    private final Label    operatorMessage = new Label("Identificação do operador");
    private final TextField usernameField  = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final Button loginButton  = new Button("Entrar");
    private final Label  loginError   = new Label();
    private final VBox   operatorScreen;

    // ── Cashier ─────────────────────────────────────────────────────────────
    private final Label  cashierTitle    = new Label("Caixa pronto");
    private final Label  cashierOperator = new Label();
    private final Button logoutButton    = new Button("Encerrar turno");
    private final VBox   cashierScreen;

    // ── Root ─────────────────────────────────────────────────────────────────
    private final VBox root = new VBox(0);

    private final PauseTransition expiry = new PauseTransition();
    private volatile TerminalRuntime runtime;
    private volatile MachineCredential activeMachineCredential;
    private boolean stopped;
    private boolean secureStorageUnavailable;

    public LucraOnePdvApplication() {
        // Setup screen
        Label setupTitle = new Label(ApplicationMetadata.name());
        setupTitle.getStyleClass().add("application-title");
        setupMessage.setWrapText(true);
        setupMessage.getStyleClass().add("application-message");
        setupConfig.setWrapText(true);
        pairingCodeField.setPromptText("Código de conexão presencial");
        pairingCodeField.setMaxWidth(320);
        setupScreen = new VBox(12, setupTitle, setupMessage, setupConfig,
                pairingCodeField, pairButton, validateButton, forgetButton);
        setupScreen.setAlignment(Pos.CENTER);

        // Operator screen
        Label operatorTitle = new Label(ApplicationMetadata.name());
        operatorTitle.getStyleClass().add("application-title");
        operatorMessage.setWrapText(true);
        operatorMessage.getStyleClass().add("application-message");
        usernameField.setPromptText("Usuário");
        usernameField.setMaxWidth(320);
        passwordField.setPromptText("Senha");
        passwordField.setMaxWidth(320);
        loginError.getStyleClass().add("application-message");
        loginError.setWrapText(true);
        loginError.setVisible(false);
        operatorScreen = new VBox(12, operatorTitle, operatorMessage,
                usernameField, passwordField, loginButton, loginError);
        operatorScreen.setAlignment(Pos.CENTER);

        // Cashier screen
        cashierTitle.getStyleClass().add("application-title");
        cashierOperator.getStyleClass().add("application-message");
        cashierScreen = new VBox(12, cashierTitle, cashierOperator, logoutButton);
        cashierScreen.setAlignment(Pos.CENTER);
    }

    @Override
    public void start(Stage stage) {
        root.getStyleClass().add("application-root");
        root.setAlignment(Pos.CENTER);
        root.getChildren().add(setupScreen);

        pairButton.setOnAction(event -> {
            String code = pairingCodeField.getText();
            pairingCodeField.clear();
            if (code.isBlank()) { setupMessage.setText("Informe um código de conexão."); return; }
            performTerminal(() -> runtime.authentication().pair(code));
        });
        validateButton.setOnAction(event -> performTerminal(() -> runtime.authentication().restore()));
        forgetButton.setOnAction(event -> performTerminal(() -> runtime.authentication().forget()));

        loginButton.setOnAction(event -> {
            String username = usernameField.getText().strip();
            String password = passwordField.getText();
            passwordField.clear();
            if (username.isBlank() || password.isBlank()) {
                showLoginError("Informe usuário e senha.");
                return;
            }
            loginError.setVisible(false);
            loginButton.setDisable(true);
            MachineCredential mc = activeMachineCredential;
            if (mc == null || runtime == null || runtime.operator().isEmpty()) {
                goToSetup();
                return;
            }
            runtime.operator().orElseThrow()
                    .login(mc, new OperatorLoginCommand(username, password))
                    .whenComplete((snap, err) -> Platform.runLater(() -> onOperatorAuth(snap, err)));
        });

        logoutButton.setOnAction(event -> {
            logoutButton.setDisable(true);
            MachineCredential mc = activeMachineCredential;
            if (mc == null || runtime == null || runtime.operator().isEmpty()) {
                goToSetup();
                return;
            }
            runtime.operator().orElseThrow().logout(mc)
                    .whenComplete((snap, err) -> Platform.runLater(this::goToOperatorLogin));
        });

        Scene scene = new Scene(root, 1000, 700);
        scene.getStylesheets().add(
                getClass().getResource("/br/com/lucraone/pdv/presentation/application.css").toExternalForm());
        stage.setTitle(ApplicationMetadata.name());
        stage.setScene(scene);
        stage.show();

        setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            runtime = TerminalRuntimeFactory.create(worker);
            return runtime;
        }, worker).thenCompose(value -> value.authentication().restore())
                .whenComplete(this::onTerminalAuth);
    }

    // ── Terminal auth flow ───────────────────────────────────────────────────

    private void performTerminal(Supplier<CompletableFuture<TerminalAuthSnapshot>> operation) {
        expiry.stop();
        setBusy(true);
        operation.get().whenComplete(this::onTerminalAuth);
    }

    private void onTerminalAuth(TerminalAuthSnapshot snapshot, Throwable error) {
        Platform.runLater(() -> {
            if (stopped) return;
            expiry.stop();
            if (error != null) {
                setupMessage.setText("Não foi possível preparar o terminal. Verifique a configuração e o armazenamento local.");
                goToSetup();
                setBusy(false);
                return;
            }
            secureStorageUnavailable = snapshot.state() == TerminalAuthState.SECURE_STORE_UNAVAILABLE;
            boolean authenticated = snapshot.state() == TerminalAuthState.AUTHENTICATED
                    || snapshot.state() == TerminalAuthState.OFFLINE_LIMITED;

            if (authenticated) {
                snapshot.expiresAt().ifPresent(this::scheduleExpiry);
                // Load machine credential from secure store so operator login can use it.
                loadMachineCredentialAsync();
                goToOperatorLogin();
            } else {
                setupMessage.setText(TerminalAuthMessages.describe(snapshot.state()));
                setupConfig.setText(runtime.backendConfigured() ? ""
                        : "Configure o endereço do servidor para conectar o terminal.");
                goToSetup();
                setBusy(false);
            }
        });
    }

    /**
     * Loads the machine credential from the secure store in the background so it is available for
     * operator login. Uses the authentication service's internal restore path to read the stored
     * credential without triggering a network call; the result is stored in {@code activeMachineCredential}.
     */
    private void loadMachineCredentialAsync() {
        CompletableFuture.runAsync(() -> {
            try {
                var stored = runtime.authentication().loadStoredCredential();
                activeMachineCredential = stored;
            } catch (RuntimeException ignored) {
                activeMachineCredential = null;
            }
        }, worker);
    }

    // ── Operator auth flow ───────────────────────────────────────────────────

    private void onOperatorAuth(OperatorAuthSnapshot snapshot, Throwable error) {
        if (stopped) return;
        if (error != null || snapshot == null) {
            showLoginError("Erro ao autenticar. Tente novamente.");
            loginButton.setDisable(false);
            return;
        }
        OperatorAuthState state = snapshot.state();
        switch (state) {
            case AUTHENTICATED -> {
                snapshot.session().ifPresent(
                        session -> cashierOperator.setText("Operador: " + session.operatorName()));
                goToCashier();
            }
            case LOGIN_FAILED -> {
                showLoginError("Usuário ou senha incorretos.");
                loginButton.setDisable(false);
            }
            case SESSION_CONFLICT -> {
                showLoginError("Este terminal possui uma sessão operacional conflitante. Encerre a sessão atual ou contate o suporte.");
                loginButton.setDisable(false);
            }
            case INVALID_RESPONSE -> { showLoginError("O servidor respondeu em formato incompatível. Contate o suporte."); loginButton.setDisable(false); }
            case NETWORK_UNAVAILABLE -> { showLoginError("Não foi possível alcançar o servidor. Verifique a conexão."); loginButton.setDisable(false); }
            case TIMEOUT -> { showLoginError("O servidor demorou para responder. Tente novamente."); loginButton.setDisable(false); }
            case ACCESS_DENIED -> { showLoginError("A sessão do terminal foi recusada. Contate o suporte."); loginButton.setDisable(false); }
            case RATE_LIMITED -> { showLoginError("Muitas tentativas. Aguarde antes de tentar novamente."); loginButton.setDisable(false); }
            case BACKEND_ERROR -> { showLoginError("O servidor apresentou um erro. Tente novamente mais tarde."); loginButton.setDisable(false); }
            case PROTOCOL_ERROR -> { showLoginError("O servidor recusou a solicitação. Verifique os dados e contate o suporte."); loginButton.setDisable(false); }
            default -> {
                showLoginError("Não foi possível autenticar. Verifique a conexão.");
                loginButton.setDisable(false);
            }
        }
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private void goToSetup() {
        root.getChildren().setAll(setupScreen);
    }

    private void goToOperatorLogin() {
        usernameField.clear();
        passwordField.clear();
        loginError.setVisible(false);
        loginButton.setDisable(false);
        root.getChildren().setAll(operatorScreen);
    }

    private void goToCashier() {
        logoutButton.setDisable(false);
        root.getChildren().setAll(cashierScreen);
    }

    private void showLoginError(String message) {
        loginError.setText(message);
        loginError.setVisible(true);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void setBusy(boolean busy) {
        pairButton.setDisable(busy || runtime == null || !runtime.backendConfigured() || secureStorageUnavailable);
        validateButton.setDisable(busy || runtime == null);
        forgetButton.setDisable(busy || runtime == null);
        pairingCodeField.setDisable(busy || runtime == null || !runtime.backendConfigured() || secureStorageUnavailable);
    }

    private void scheduleExpiry(Instant instant) {
        long millis = Math.max(1, java.time.Duration.between(Instant.now(), instant).toMillis() + 1);
        expiry.setDuration(Duration.millis(millis));
        expiry.setOnFinished(event -> performTerminal(() -> runtime.authentication().restore()));
        expiry.playFromStart();
    }

    @Override
    public void stop() {
        stopped = true;
        pairingCodeField.clear();
        passwordField.clear();
        activeMachineCredential = null;
        expiry.stop();
        worker.execute(() -> { if (runtime != null) runtime.close(); });
        worker.shutdown();
    }

    public static void main(String[] args) { launch(args); }
}
