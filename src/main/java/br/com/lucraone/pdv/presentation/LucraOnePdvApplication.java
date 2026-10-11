package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.ApplicationMetadata;
import br.com.lucraone.pdv.application.auth.TerminalAuthSnapshot;
import br.com.lucraone.pdv.application.auth.TerminalAuthState;
import br.com.lucraone.pdv.application.auth.TerminalAuthorizationMonitor;
import br.com.lucraone.pdv.application.auth.LocalTerminalIdentity;
import br.com.lucraone.pdv.application.operator.OperatorAuthSnapshot;
import br.com.lucraone.pdv.application.operator.OperatorAuthState;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorLoginCommand;
import br.com.lucraone.pdv.infrastructure.auth.TerminalRuntime;
import br.com.lucraone.pdv.infrastructure.auth.TerminalRuntimeFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
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

    // ── Remote authorization suspended ──────────────────────────────────────
    private final Label suspendedTitle = new Label("Terminal sem autorização");
    private final Label suspendedMessage = new Label();
    private final Label suspendedIdentity = new Label();
    private final Label suspendedCheckedAt = new Label();
    private final PasswordField recoveryCodeField = new PasswordField();
    private final Button retryAuthorizationButton = new Button("Verificar novamente");
    private final Button recoverCredentialButton = new Button("Recuperar credencial");
    private final Button closeApplicationButton = new Button("Fechar aplicativo");
    private final VBox suspendedScreen;

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
    private volatile LocalTerminalIdentity localTerminalIdentity;
    private volatile TerminalAuthorizationMonitor authorizationMonitor;
    private java.util.function.Consumer<OperatorAuthSnapshot> operatorListener;
    private boolean authorizationDenied;
    private volatile boolean terminalAuthorizationValid;
    private final AtomicLong operatorUiGeneration = new AtomicLong();
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

        suspendedTitle.getStyleClass().add("application-title");
        suspendedMessage.getStyleClass().add("application-message");
        suspendedMessage.setWrapText(true);
        suspendedIdentity.setWrapText(true);
        suspendedCheckedAt.setWrapText(true);
        recoveryCodeField.setPromptText("Código de recuperação fornecido pelo administrador");
        recoveryCodeField.setMaxWidth(360);
        suspendedScreen = new VBox(12, suspendedTitle, suspendedIdentity, suspendedMessage,
                suspendedCheckedAt, recoveryCodeField, retryAuthorizationButton,
                recoverCredentialButton, closeApplicationButton);
        suspendedScreen.setAlignment(Pos.CENTER);

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
        retryAuthorizationButton.setOnAction(event -> performTerminal(() -> runtime.authentication().restore()));
        recoverCredentialButton.setOnAction(event -> {
            String code = recoveryCodeField.getText();
            recoveryCodeField.clear();
            if (runtime == null || runtime.recovery().isEmpty()) {
                showSuspended("A recuperação segura não está disponível nesta instalação.", true);
                return;
            }
            if (code.length() != 19) {
                showSuspended("Informe o código de recuperação de 19 caracteres fornecido pelo administrador.", true);
                return;
            }
            if (authorizationMonitor != null) authorizationMonitor.invalidatePending();
            setSuspendedBusy(true);
            runtime.recovery().orElseThrow().recover(code).whenComplete(this::onCredentialRecovery);
        });
        closeApplicationButton.setOnAction(event -> Platform.exit());

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
            long generation = operatorUiGeneration.incrementAndGet();
            runtime.operator().orElseThrow()
                    .login(mc, new OperatorLoginCommand(username, password))
                    .whenComplete((snap, err) -> Platform.runLater(() -> onOperatorAuth(snap, err, generation)));
        });

        logoutButton.setOnAction(event -> {
            logoutButton.setDisable(true);
            operatorUiGeneration.incrementAndGet();
            MachineCredential mc = activeMachineCredential;
            if (mc == null || runtime == null || runtime.operator().isEmpty()) {
                goToSetup();
                return;
            }
            runtime.operator().orElseThrow().logout(mc)
                    .whenComplete((snap, err) -> Platform.runLater(() -> {
                        if (terminalAuthorizationValid && activeMachineCredential != null) goToOperatorLogin();
                        else if (!root.getChildren().contains(suspendedScreen)) showSuspended(
                                "A autorização online do terminal precisa ser confirmada antes de voltar ao login.", false);
                    }));
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
            localTerminalIdentity = runtime.authentication().localIdentity();
            operatorListener = snapshot -> Platform.runLater(() -> onOperatorRevalidation(snapshot));
            runtime.operator().ifPresent(service -> service.addListener(operatorListener));
            return runtime;
        }, worker).thenCompose(value -> value.authentication().restore())
                .whenComplete(this::onTerminalAuth);
    }

    // ── Terminal auth flow ───────────────────────────────────────────────────

    private void performTerminal(Supplier<CompletableFuture<TerminalAuthSnapshot>> operation) {
        if (authorizationMonitor != null) authorizationMonitor.invalidatePending();
        expiry.stop();
        setBusy(true);
        operation.get().whenComplete(this::onTerminalAuth);
    }

    private void onTerminalAuth(TerminalAuthSnapshot snapshot, Throwable error) {
        Platform.runLater(() -> {
            if (stopped) return;
            expiry.stop();
            if (error != null) {
                terminalAuthorizationValid = false;
                showSuspended("Não foi possível confirmar a autorização com o servidor. Tente novamente quando a conexão estiver disponível.", false);
                return;
            }
            secureStorageUnavailable = snapshot.state() == TerminalAuthState.SECURE_STORE_UNAVAILABLE;
            lastAuthorizationCheck = Instant.now();
            boolean authenticated = snapshot.state() == TerminalAuthState.AUTHENTICATED;
            terminalAuthorizationValid = authenticated;

            if (authenticated) {
                authorizationDenied = false;
                snapshot.expiresAt().ifPresent(this::scheduleExpiry);
                loadMachineCredentialAsync();
                startAuthorizationMonitor();
                goToOperatorLogin();
            } else {
                boolean noCredential = snapshot.state() == TerminalAuthState.PROVISIONED_NO_CREDENTIAL;
                boolean denied = snapshot.failureKind().filter(kind -> kind == br.com.lucraone.pdv.application.api.PdvFailureKind.UNAUTHENTICATED
                        || kind == br.com.lucraone.pdv.application.api.PdvFailureKind.FORBIDDEN).isPresent();
                if (noCredential || denied || snapshot.state() == TerminalAuthState.REAUTHENTICATION_REQUIRED) {
                    authorizationDenied = true;
                    suspendOperatorSession();
                    String message = "Este computador não está autorizado a operar no momento. Entre em contato com o administrador para verificar o terminal e recuperar sua credencial, se necessário.";
                    showSuspended(message, true);
                    closeMonitorAfterDenial();
                } else if (snapshot.state() == TerminalAuthState.OFFLINE_LIMITED
                        || snapshot.failureKind().filter(kind -> kind == br.com.lucraone.pdv.application.api.PdvFailureKind.NETWORK_UNAVAILABLE
                        || kind == br.com.lucraone.pdv.application.api.PdvFailureKind.TIMEOUT).isPresent()) {
                    suspendOperatorSession();
                    showSuspended("Sem conexão com o servidor. O caixa permanece suspenso até uma validação online.", false);
                    startAuthorizationMonitor();
                } else if (snapshot.state() == TerminalAuthState.VALIDATION_FAILED) {
                    suspendOperatorSession();
                    showSuspended("O servidor não confirmou a autorização. Tente novamente mais tarde.", false);
                    startAuthorizationMonitor();
                } else {
                    setupMessage.setText(TerminalAuthMessages.describe(snapshot.state()));
                    setupConfig.setText(runtime.backendConfigured() ? "" : "Configure o endereço do servidor para conectar o terminal.");
                    goToSetup();
                    setBusy(false);
                }
            }
        });
    }

    private Instant lastAuthorizationCheck;

    private void onMonitoredTerminalAuth(TerminalAuthSnapshot snapshot) {
        Platform.runLater(() -> applyMonitoredTerminalAuth(snapshot));
    }

    private void applyMonitoredTerminalAuth(TerminalAuthSnapshot snapshot) {
        if (stopped || snapshot == null) return;
        lastAuthorizationCheck = Instant.now();
        if (snapshot.state() == TerminalAuthState.AUTHENTICATED) {
            terminalAuthorizationValid = true;
            if (authorizationDenied || root.getChildren().contains(suspendedScreen)) {
                authorizationDenied = false;
                runtime.operator().ifPresent(service -> service.invalidateLocal());
                loadMachineCredentialAsync();
                goToOperatorLogin();
            }
            updateAuthorizationTimestamp();
            return;
        }
        terminalAuthorizationValid = false;
        boolean denied = snapshot.state() == TerminalAuthState.PROVISIONED_NO_CREDENTIAL
                || snapshot.state() == TerminalAuthState.REAUTHENTICATION_REQUIRED
                || snapshot.failureKind().filter(kind -> kind == br.com.lucraone.pdv.application.api.PdvFailureKind.UNAUTHENTICATED
                || kind == br.com.lucraone.pdv.application.api.PdvFailureKind.FORBIDDEN).isPresent();
        if (denied) {
            authorizationDenied = true;
            suspendOperatorSession();
            showSuspended("Este computador não está autorizado a operar no momento. Entre em contato com o administrador para verificar o terminal e recuperar sua credencial, se necessário.", true);
            closeMonitorAfterDenial();
        } else if (snapshot.failureKind().filter(kind -> kind == br.com.lucraone.pdv.application.api.PdvFailureKind.NETWORK_UNAVAILABLE
                || kind == br.com.lucraone.pdv.application.api.PdvFailureKind.TIMEOUT).isPresent()
                || snapshot.state() == TerminalAuthState.OFFLINE_LIMITED) {
            suspendOperatorSession();
            showSuspended("Sem conexão com o servidor. O caixa permanece suspenso até uma validação online.", false);
        } else if (snapshot.state() == TerminalAuthState.VALIDATION_FAILED) {
            suspendOperatorSession();
            showSuspended("O servidor não confirmou a autorização. Tente novamente mais tarde.", false);
        }
        updateAuthorizationTimestamp();
    }

    private void startAuthorizationMonitor() {
        if (authorizationMonitor != null) { authorizationMonitor.start(); return; }
        long seconds = Math.max(10, Long.getLong("lucraone.pdv.authorization-poll-seconds", 30L));
        authorizationMonitor = new TerminalAuthorizationMonitor(() -> runtime.authentication().restore(),
                snapshot -> onMonitoredTerminalAuth(snapshot), java.time.Duration.ofSeconds(seconds));
        authorizationMonitor.start();
    }

    private void closeMonitorAfterDenial() {
        TerminalAuthorizationMonitor monitor = authorizationMonitor;
        authorizationMonitor = null;
        if (monitor != null) monitor.close();
    }

    private void suspendOperatorSession() {
        operatorUiGeneration.incrementAndGet();
        terminalAuthorizationValid = false;
        expiry.stop();
        activeMachineCredential = null;
        runtime.operator().ifPresent(service -> service.invalidateLocal());
    }

    private void showSuspended(String message, boolean recoveryAllowed) {
        suspendedMessage.setText(message);
        LocalTerminalIdentity identity = localTerminalIdentity;
        suspendedIdentity.setText(identity == null ? "Terminal provisionado" :
                "Terminal: " + identity.terminalId() + "    Filial: " + identity.branchId());
        updateAuthorizationTimestamp();
        recoverCredentialButton.setVisible(recoveryAllowed && runtime != null && runtime.recovery().isPresent());
        recoverCredentialButton.setManaged(recoverCredentialButton.isVisible());
        recoveryCodeField.setVisible(recoverCredentialButton.isVisible());
        recoveryCodeField.setManaged(recoverCredentialButton.isVisible());
        retryAuthorizationButton.setDisable(false);
        recoverCredentialButton.setDisable(false);
        root.getChildren().setAll(suspendedScreen);
    }

    private void setSuspendedBusy(boolean busy) {
        retryAuthorizationButton.setDisable(busy);
        recoverCredentialButton.setDisable(busy);
        suspendedMessage.setText(busy ? "Validando a credencial de recuperação com o servidor…" : suspendedMessage.getText());
    }

    private void updateAuthorizationTimestamp() {
        if (lastAuthorizationCheck == null) suspendedCheckedAt.setText("Última verificação: ainda não realizada");
        else suspendedCheckedAt.setText("Última verificação: " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(lastAuthorizationCheck));
    }

    private void onOperatorRevalidation(OperatorAuthSnapshot snapshot) {
        if (stopped || snapshot == null) return;
        if (authorizationDenied || root.getChildren().contains(suspendedScreen)) return;
        switch (snapshot.state()) {
            case SESSION_INVALID, SESSION_CONFLICT -> {
                expiry.stop();
                goToOperatorLogin();
                showLoginError("A sessão do operador não está mais autorizada. Entre novamente.");
            }
            case ACCESS_DENIED -> {
                if (authorizationMonitor != null) authorizationMonitor.invalidatePending();
                runtime.authentication().restore().whenComplete(this::onTerminalAuth);
            }
            case NETWORK_UNAVAILABLE, TIMEOUT -> {
                if (root.getChildren().contains(cashierScreen)) {
                    suspendOperatorSession();
                    showSuspended("Sem conexão com o servidor. O caixa permanece suspenso até uma validação online.", false);
                }
            }
            default -> { }
        }
    }

    /**
     * Loads the machine credential from the secure store in the background so it is available for
     * operator login. Uses the authentication service's internal restore path to read the stored
     * credential without triggering a network call; the result is stored in {@code activeMachineCredential}.
     */
    private void loadMachineCredentialAsync() {
        loginButton.setDisable(true);
        CompletableFuture.runAsync(() -> {
            try {
                var stored = runtime.authentication().loadStoredCredential();
                activeMachineCredential = stored;
            } catch (RuntimeException ignored) {
                activeMachineCredential = null;
            }
        }, worker).whenComplete((ignored, error) -> Platform.runLater(() -> {
            if (!stopped && root.getChildren().contains(operatorScreen)) loginButton.setDisable(activeMachineCredential == null);
        }));
    }

    private void onCredentialRecovery(TerminalAuthSnapshot snapshot, Throwable error) {
        if (error == null) { onTerminalAuth(snapshot, null); return; }
        Platform.runLater(() -> {
            if (stopped) return;
            Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            String message;
            if (cause instanceof br.com.lucraone.pdv.application.api.PdvApiException api
                    && api.kind() == br.com.lucraone.pdv.application.api.PdvFailureKind.CREDENTIAL_RECOVERY_FAILED) {
                message = "Código inválido, expirado ou não autorizado. Confirme com o administrador se o terminal foi desbloqueado e se o código ainda está válido.";
            } else if (cause instanceof br.com.lucraone.pdv.application.api.PdvApiException api
                    && api.kind() == br.com.lucraone.pdv.application.api.PdvFailureKind.RATE_LIMITED) {
                message = "Muitas tentativas. Aguarde antes de tentar novamente.";
            } else if (cause instanceof br.com.lucraone.pdv.application.api.PdvApiException api
                    && (api.kind() == br.com.lucraone.pdv.application.api.PdvFailureKind.NETWORK_UNAVAILABLE
                    || api.kind() == br.com.lucraone.pdv.application.api.PdvFailureKind.TIMEOUT
                    || api.kind() == br.com.lucraone.pdv.application.api.PdvFailureKind.SERVER_ERROR)) {
                message = "Sem resposta válida do servidor. A tentativa protegida foi preservada; tente novamente quando a conexão estiver disponível.";
            } else {
                message = "Não foi possível concluir a recuperação segura. Procure o administrador antes de tentar outro código.";
            }
            setSuspendedBusy(false);
            showSuspended(message, true);
        });
    }

    // ── Operator auth flow ───────────────────────────────────────────────────

    private void onOperatorAuth(OperatorAuthSnapshot snapshot, Throwable error, long generation) {
        if (stopped) return;
        if (generation != operatorUiGeneration.get()) return;
        if (error != null || snapshot == null) {
            showLoginError("Erro ao autenticar. Tente novamente.");
            loginButton.setDisable(false);
            return;
        }
        OperatorAuthState state = snapshot.state();
        switch (state) {
            case AUTHENTICATED -> {
                if (!terminalAuthorizationValid || activeMachineCredential == null) {
                    suspendOperatorSession();
                    showSuspended("A autorização online do terminal precisa ser confirmada antes de abrir o caixa.", false);
                    return;
                }
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
            case ACCESS_DENIED -> {
                // A 401/403 from operator login can mean bad human credentials or revoked
                // machine authority. Revalidate the machine before deciding which UI to show.
                if (authorizationMonitor != null) authorizationMonitor.invalidatePending();
                runtime.authentication().restore().whenComplete((terminal, terminalError) -> Platform.runLater(() -> {
                    if (stopped || generation != operatorUiGeneration.get()) return;
                    if (terminalError == null && terminal != null
                            && terminal.state() == TerminalAuthState.AUTHENTICATED) {
                        terminalAuthorizationValid = true;
                        loadMachineCredentialAsync();
                        showLoginError("Usuário ou senha não reconhecidos. Confira os dados e tente novamente.");
                        loginButton.setDisable(false);
                    } else {
                        onTerminalAuth(terminal, terminalError);
                    }
                }));
            }
            case INVALID_RESPONSE -> { showLoginError("O servidor respondeu em formato incompatível. Contate o suporte."); loginButton.setDisable(false); }
            case NETWORK_UNAVAILABLE -> { showLoginError("Não foi possível alcançar o servidor. Verifique a conexão."); loginButton.setDisable(false); }
            case TIMEOUT -> { showLoginError("O servidor demorou para responder. Tente novamente."); loginButton.setDisable(false); }
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
        loginButton.setDisable(activeMachineCredential == null || authorizationDenied);
        root.getChildren().setAll(operatorScreen);
    }

    private void goToCashier() {
        logoutButton.setDisable(false);
        root.getChildren().setAll(cashierScreen);
        if (authorizationMonitor != null) authorizationMonitor.checkNow();
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
        if (authorizationMonitor != null) authorizationMonitor.close();
        if (runtime != null && operatorListener != null)
            runtime.operator().ifPresent(service -> service.removeListener(operatorListener));
        worker.execute(() -> { if (runtime != null) runtime.close(); });
        worker.shutdown();
    }

    public static void main(String[] args) { launch(args); }
}
