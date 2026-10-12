package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.ApplicationMetadata;
import br.com.lucraone.pdv.application.auth.TerminalAuthSnapshot;
import br.com.lucraone.pdv.application.auth.TerminalAuthState;
import br.com.lucraone.pdv.application.auth.TerminalAuthorizationMonitor;
import br.com.lucraone.pdv.application.auth.LocalTerminalIdentity;
import br.com.lucraone.pdv.application.operator.OperatorAuthSnapshot;
import br.com.lucraone.pdv.application.operator.OperatorAuthState;
import br.com.lucraone.pdv.application.api.MachineCredential;
import br.com.lucraone.pdv.application.api.OperatorInfo;
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
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
    private final Label setupIdentity = new Label("Instalação local: carregando…");
    private final Label setupConnection = new Label("Verificando configuração do servidor…");
    private final PasswordField pairingCodeField = new PasswordField();
    private final Button pairButton     = new Button("Conectar terminal");
    private final Button validateButton = new Button("Verificar conexão");
    private final Button forgetButton   = new Button("Esquecer autenticação local");
    private final HBox setupScreen;

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
    private final ComboBox<OperatorInfo> operatorPicker = new ComboBox<>();
    private final Label operatorRosterStatus = new Label("A lista será carregada após a validação do terminal.");
    private final Button loginButton  = new Button("Entrar");
    private final Label  loginError   = new Label();
    private final Label loginContext = new Label("Terminal vinculado");
    private final Label cashierContext = new Label("Terminal vinculado");
    private final HBox   operatorScreen;

    // ── Cashier ─────────────────────────────────────────────────────────────
    private final Label  cashierTitle    = new Label("Caixa pronto");
    private final Label  cashierOperator = new Label();
    private final Button logoutButton    = new Button("Encerrar turno");
    private final VBox   cashierScreen;
    private final TextField productEntryPlaceholder = new TextField();
    private final Label cashierClock = new Label();

    // ── Root ─────────────────────────────────────────────────────────────────
    private final VBox root = new VBox(0);
    private Node currentScreen;

    private final PauseTransition expiry = new PauseTransition();
    private volatile TerminalRuntime runtime;
    private volatile MachineCredential activeMachineCredential;
    private volatile LocalTerminalIdentity localTerminalIdentity;
    private volatile java.util.UUID installationId;
    private volatile String verifiedBusinessHeader;
    private volatile TerminalAuthorizationMonitor authorizationMonitor;
    private java.util.function.Consumer<OperatorAuthSnapshot> operatorListener;
    private boolean authorizationDenied;
    private volatile boolean terminalAuthorizationValid;
    private final AtomicLong operatorUiGeneration = new AtomicLong();
    private boolean stopped;
    private boolean secureStorageUnavailable;

    public LucraOnePdvApplication() {
        setupMessage.setWrapText(true);
        setupMessage.getStyleClass().addAll("feedback-message", "feedback-info");
        setupConfig.setWrapText(true);
        setupConfig.getStyleClass().add("muted-text");
        setupIdentity.getStyleClass().add("mono-text");
        setupConnection.getStyleClass().setAll("status-pill", "status-neutral");
        pairingCodeField.setPromptText("Cole o código de pareamento");
        pairingCodeField.setMaxWidth(Double.MAX_VALUE);
        pairButton.setDefaultButton(true);
        pairButton.getStyleClass().add("button-primary");
        pairButton.setMaxWidth(Double.MAX_VALUE);
        validateButton.setText("Verificar novamente");
        validateButton.getStyleClass().add("button-secondary");
        validateButton.setMaxWidth(Double.MAX_VALUE);
        forgetButton.getStyleClass().add("button-quiet");

        VBox setupIntro = new VBox(16,
                eyebrow("CONFIGURAÇÃO DO TERMINAL"),
                heading("Conecte este caixa à sua loja"),
                body("O pareamento associa este computador ao terminal autorizado no painel LucraOne."),
                stepRow("01", "Código de conexão", "Solicite o código ao administrador."),
                stepRow("02", "Validação segura", "O terminal será identificado pelo servidor."),
                stepRow("03", "Acesso do operador", "Depois da validação, entre com seu usuário."));
        setupIntro.getStyleClass().add("setup-intro");

        VBox setupCard = new VBox(12,
                eyebrow("VINCULAR TERMINAL"),
                new Label("Código de pareamento"), pairingCodeField,
                pairButton, validateButton, setupMessage, setupConfig,
                new javafx.scene.control.Separator(),
                infoLine("IDENTIFICADOR LOCAL", setupIdentity),
                infoLine("CONECTIVIDADE", setupConnection),
                forgetButton);
        setupCard.getStyleClass().addAll("surface-card", "setup-card");
        setupScreen = new HBox(28, setupIntro, setupCard);
        setupScreen.setAlignment(Pos.CENTER);
        setupScreen.setMaxHeight(Region.USE_PREF_SIZE);
        setupIntro.setMaxHeight(Region.USE_PREF_SIZE);
        setupCard.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(setupIntro, Priority.ALWAYS);
        HBox.setHgrow(setupCard, Priority.ALWAYS);
        setupIntro.setMaxWidth(650);
        setupCard.setMaxWidth(560);

        suspendedTitle.getStyleClass().add("application-title");
        suspendedMessage.getStyleClass().add("application-message");
        suspendedMessage.setWrapText(true);
        suspendedIdentity.setWrapText(true);
        suspendedCheckedAt.setWrapText(true);
        recoveryCodeField.setPromptText("Código de recuperação fornecido pelo administrador");
        recoveryCodeField.setMaxWidth(Double.MAX_VALUE);
        suspendedIdentity.getStyleClass().add("mono-text");
        suspendedCheckedAt.getStyleClass().add("muted-text");
        retryAuthorizationButton.getStyleClass().add("button-primary");
        retryAuthorizationButton.setMaxWidth(Double.MAX_VALUE);
        recoverCredentialButton.getStyleClass().add("button-secondary");
        recoverCredentialButton.setMaxWidth(Double.MAX_VALUE);
        closeApplicationButton.getStyleClass().add("button-quiet");
        closeApplicationButton.setMaxWidth(Double.MAX_VALUE);
        Label suspendedIcon = new Label("!");
        suspendedIcon.getStyleClass().add("status-icon-danger");
        VBox suspendedCard = new VBox(16, suspendedIcon,
                eyebrow("AUTORIZAÇÃO DO TERMINAL"), suspendedTitle,
                suspendedIdentity, suspendedMessage, suspendedCheckedAt,
                recoveryCodeField, retryAuthorizationButton,
                recoverCredentialButton, closeApplicationButton);
        suspendedCard.getStyleClass().addAll("surface-card", "suspended-card");
        suspendedScreen = new VBox(suspendedCard);
        suspendedScreen.setAlignment(Pos.CENTER);
        suspendedScreen.setMaxHeight(Region.USE_PREF_SIZE);
        suspendedCard.setMaxHeight(Region.USE_PREF_SIZE);

        operatorMessage.setWrapText(true);
        operatorMessage.getStyleClass().addAll("feedback-message", "feedback-info");
        usernameField.setPromptText("Nome de usuário");
        usernameField.setMaxWidth(Double.MAX_VALUE);
        operatorPicker.setPromptText("Selecionar operador autorizado");
        operatorPicker.setMaxWidth(Double.MAX_VALUE);
        operatorPicker.setDisable(true);
        operatorPicker.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(OperatorInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName() + "  ·  " + item.username());
            }
        });
        operatorPicker.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(OperatorInfo item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? operatorPicker.getPromptText()
                        : item.displayName() + "  ·  " + item.username());
            }
        });
        operatorPicker.setOnAction(event -> {
            OperatorInfo selected = operatorPicker.getValue();
            if (selected != null) usernameField.setText(selected.username());
        });
        operatorRosterStatus.getStyleClass().add("muted-text");
        passwordField.setPromptText("Senha do operador");
        passwordField.setMaxWidth(Double.MAX_VALUE);
        loginButton.setText("Entrar no caixa");
        loginButton.setDefaultButton(true);
        loginButton.getStyleClass().add("button-primary");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginContext.getStyleClass().add("context-summary");
        cashierContext.getStyleClass().add("context-summary");
        loginError.getStyleClass().addAll("feedback-message", "feedback-danger");
        loginError.setWrapText(true);
        loginError.setVisible(false);
        loginError.setManaged(false);
        VBox loginContextCard = new VBox(14,
                eyebrow("SESSÃO SEGURA"), heading("Abertura do caixa"),
                body("Identifique-se para iniciar seu turno. Este terminal já está vinculado ao contexto autorizado."),
                loginContext,
                infoLine("STATUS", new Label("Terminal autorizado")));
        loginContextCard.getStyleClass().add("login-context-card");
        VBox loginCard = new VBox(11,
                eyebrow("IDENTIFICAÇÃO DO OPERADOR"), heading("Bem-vindo"),
                operatorMessage, new Label("Operadores autorizados"), operatorPicker,
                operatorRosterStatus, new Label("Ou informe o usuário"), usernameField,
                new Label("Senha"), passwordField, loginButton, loginError);
        loginCard.getStyleClass().addAll("surface-card", "login-card");
        operatorScreen = new HBox(28, loginContextCard, loginCard);
        operatorScreen.setAlignment(Pos.CENTER);
        operatorScreen.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(loginContextCard, Priority.ALWAYS);
        HBox.setHgrow(loginCard, Priority.ALWAYS);
        loginContextCard.setMaxWidth(560);
        loginCard.setMaxWidth(520);
        loginContextCard.setMaxHeight(Region.USE_PREF_SIZE);
        loginCard.setMaxHeight(Region.USE_PREF_SIZE);

        cashierTitle.getStyleClass().add("cashier-heading");
        cashierOperator.getStyleClass().setAll("status-pill", "status-positive");
        productEntryPlaceholder.setPromptText("Código de barras ou produto");
        productEntryPlaceholder.setEditable(false);
        productEntryPlaceholder.setFocusTraversable(false);
        productEntryPlaceholder.getStyleClass().add("scan-placeholder");
        VBox productHeader = new VBox(4, eyebrow("OPERAÇÃO DO CAIXA"), cashierTitle,
                body("Leitura de produtos disponível em breve."));
        HBox saleColumns = new HBox(12,
                tableHeading("ITEM", 55), tableHeading("PRODUTO", 1),
                tableHeading("QTD", 70), tableHeading("UNITÁRIO", 110), tableHeading("TOTAL", 110));
        saleColumns.getStyleClass().add("sale-table-header");
        VBox emptySale = new VBox(8,
                new Label("＋"), new Label("Nenhum produto adicionado"),
                body("Esta área será habilitada após a integração do catálogo."));
        emptySale.setAlignment(Pos.CENTER);
        emptySale.getStyleClass().add("empty-state");
        VBox saleArea = new VBox(14, productEntryPlaceholder, saleColumns, emptySale);
        saleArea.getStyleClass().addAll("surface-card", "sale-area");
        VBox.setVgrow(emptySale, Priority.ALWAYS);

        VBox saleSummary = new VBox(18,
                eyebrow("RESUMO DA VENDA"),
                summaryBlock("SUBTOTAL", "R$ 0,00"),
                summaryBlock("DESCONTO", "R$ 0,00"),
                new javafx.scene.control.Separator(),
                summaryBlock("TOTAL DA VENDA", "R$ 0,00", true),
                body("Pagamento disponível após a integração comercial."));
        saleSummary.getStyleClass().addAll("surface-card", "sale-summary");
        HBox.setHgrow(saleArea, Priority.ALWAYS);
        saleArea.setMaxWidth(Double.MAX_VALUE);
        saleSummary.setMinWidth(300);
        saleSummary.setPrefWidth(350);
        saleSummary.setMaxWidth(410);
        HBox cashierBody = new HBox(18, saleArea, saleSummary);
        cashierBody.setAlignment(Pos.CENTER);
        VBox cashierMain = new VBox(18, productHeader, cashierBody);
        cashierMain.setPadding(new Insets(16, 24, 14, 24));
        VBox.setVgrow(cashierBody, Priority.ALWAYS);
        cashierClock.getStyleClass().add("muted-text");
        logoutButton.setText("Encerrar turno");
        logoutButton.getStyleClass().add("button-danger-outline");
        Button refreshButton = new Button("Atualizar status");
        refreshButton.getStyleClass().add("button-secondary");
        refreshButton.setOnAction(event -> performTerminal(this::restoreTerminal));
        Button verifyButton = new Button("Verificar terminal");
        verifyButton.getStyleClass().add("button-secondary");
        verifyButton.setOnAction(event -> performTerminal(this::restoreTerminal));
        Region cashierSpacer = new Region();
        HBox cashierActions = new HBox(10, cashierOperator, cashierClock,
                cashierSpacer, refreshButton, verifyButton, logoutButton);
        cashierActions.getStyleClass().add("cashier-action-bar");
        cashierActions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cashierSpacer, Priority.ALWAYS);
        cashierScreen = new VBox(0, cashierMain, cashierActions);
        VBox.setVgrow(cashierMain, Priority.ALWAYS);
    }

    @Override
    public void start(Stage stage) {
        root.getStyleClass().add("application-root");
        root.setAlignment(Pos.TOP_CENTER);
        root.setFillWidth(true);
        root.getChildren().setAll(screenHeader("Preparando terminal"), screenFrame(setupScreen), appFooter());
        currentScreen = setupScreen;

        pairButton.setOnAction(event -> {
            String code = pairingCodeField.getText();
            pairingCodeField.clear();
            if (code.isBlank()) { setupMessage.setText("Informe um código de conexão."); return; }
            performTerminal(() -> runtime.authentication().pair(code));
        });
        validateButton.setOnAction(event -> performTerminal(this::restoreTerminal));
        forgetButton.setOnAction(event -> performTerminal(() -> runtime.authentication().forget()));
        retryAuthorizationButton.setOnAction(event -> performTerminal(this::restoreTerminal));
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
                        else if (currentScreen != suspendedScreen) showSuspended(
                                "A autorização online do terminal precisa ser confirmada antes de voltar ao login.", false);
                    }));
        });

        Scene scene = new Scene(root);
        scene.getStylesheets().add(
                getClass().getResource("/br/com/lucraone/pdv/presentation/application.css").toExternalForm());
        stage.setTitle(ApplicationMetadata.name());
        stage.setScene(scene);
        stage.show();
        // Let the window manager maximize the decorated window while retaining normal
        // minimize/restore controls. This also applies when startup selects another screen.
        stage.setMaximized(true);

        setBusy(true);
        CompletableFuture.supplyAsync(() -> {
            runtime = TerminalRuntimeFactory.create(worker);
            installationId = runtime.authentication().installationId();
            try { localTerminalIdentity = runtime.authentication().localIdentity(); }
            catch (RuntimeException notYetPaired) { localTerminalIdentity = null; }
            operatorListener = snapshot -> Platform.runLater(() -> onOperatorRevalidation(snapshot));
            runtime.operator().ifPresent(service -> service.addListener(operatorListener));
            return runtime;
        }, worker).thenCompose(value -> restoreTerminal())
                .whenComplete(this::onTerminalAuth);
    }

    // ── Terminal auth flow ───────────────────────────────────────────────────

    /** Resume a durable credential rotation before restore can reject the previous machine token. */
    private CompletableFuture<TerminalAuthSnapshot> restoreTerminal() {
        TerminalRuntime active = runtime;
        if (active == null) return CompletableFuture.completedFuture(
                TerminalAuthSnapshot.of(TerminalAuthState.LOCAL_STATE_UNAVAILABLE));
        return CompletableFuture.supplyAsync(() -> {
            try {
                active.rotation().ifPresent(rotation -> rotation.recoverPending(active.authentication().loadStoredCredential()));
                return null;
            } catch (br.com.lucraone.pdv.application.auth.TerminalAuthException error) {
                TerminalAuthState state = switch (error.kind()) {
                    case CREDENTIAL_EXPIRED -> TerminalAuthState.REAUTHENTICATION_REQUIRED;
                    case SECURE_STORE_UNAVAILABLE -> TerminalAuthState.SECURE_STORE_UNAVAILABLE;
                    case PERSISTENCE_FAILED, CONTEXT_MISMATCH, COMPENSATION_FAILED -> TerminalAuthState.INCONSISTENT_STATE;
                    default -> TerminalAuthState.VALIDATION_FAILED;
                };
                return TerminalAuthSnapshot.of(state);
            } catch (br.com.lucraone.pdv.application.auth.CredentialStorageException error) {
                return TerminalAuthSnapshot.of(error.kind() == br.com.lucraone.pdv.application.auth.CredentialStorageException.Kind.UNAVAILABLE
                        ? TerminalAuthState.SECURE_STORE_UNAVAILABLE : TerminalAuthState.INCONSISTENT_STATE);
            } catch (RuntimeException error) {
                return TerminalAuthSnapshot.of(TerminalAuthState.LOCAL_STATE_UNAVAILABLE);
            }
        }, worker).thenCompose(snapshot -> snapshot == null
                ? active.authentication().restore() : CompletableFuture.completedFuture(snapshot));
    }

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
            if (runtime != null) {
                setupConnection.setText(runtime.backendConfigured() ? "Servidor configurado" : "Servidor não configurado");
                setupConnection.getStyleClass().setAll("status-pill",
                        runtime.backendConfigured() ? "status-positive" : "status-warning");
            }
            if (installationId != null) setupIdentity.setText(installationId.toString());
            if (localTerminalIdentity != null) {
                loginContext.setText(operatorFacingContext(localTerminalIdentity));
                cashierContext.setText(loginContext.getText());
            }
            if (runtime != null) runtime.authentication().lastVerifiedContext().ifPresent(context -> {
                String company = context.company().tradeName();
                String branch = context.branch().name();
                String terminal = context.terminal().name();
                loginContext.setText(company + "\n" + branch + " — " + terminal);
                cashierContext.setText(loginContext.getText());
                verifiedBusinessHeader = branch + "  ·  " + terminal;
            });
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
            if (authorizationDenied || currentScreen == suspendedScreen) {
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
        authorizationMonitor = new TerminalAuthorizationMonitor(this::restoreTerminal,
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
        showScreen(suspendedScreen, "Acesso suspenso");
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
        if (authorizationDenied || currentScreen == suspendedScreen) return;
        switch (snapshot.state()) {
            case SESSION_INVALID, SESSION_CONFLICT -> {
                expiry.stop();
                goToOperatorLogin();
                showLoginError("A sessão do operador não está mais autorizada. Entre novamente.");
            }
            case ACCESS_DENIED -> {
                if (authorizationMonitor != null) authorizationMonitor.invalidatePending();
                restoreTerminal().whenComplete(this::onTerminalAuth);
            }
            case NETWORK_UNAVAILABLE, TIMEOUT -> {
                if (currentScreen == cashierScreen) {
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
            if (!stopped && currentScreen == operatorScreen) {
                loginButton.setDisable(activeMachineCredential == null);
                loadOperatorChoices(activeMachineCredential);
            }
        }));
    }

    private void loadOperatorChoices(MachineCredential credential) {
        operatorPicker.getItems().clear();
        operatorPicker.setValue(null);
        operatorPicker.setDisable(true);
        if (credential == null || runtime == null || runtime.operator().isEmpty()) {
            operatorRosterStatus.setText("Lista indisponível. Informe seu usuário manualmente.");
            return;
        }
        operatorRosterStatus.setText("Carregando operadores autorizados…");
        runtime.operator().orElseThrow().listOperators(credential).whenComplete((result, error) -> Platform.runLater(() -> {
            if (stopped || currentScreen != operatorScreen) return;
            if (error != null || result == null) {
                operatorRosterStatus.setText("Lista indisponível. Informe seu usuário manualmente.");
                return;
            }
            if (localTerminalIdentity != null && !localTerminalIdentity.branchId().equals(result.branchId())) {
                operatorRosterStatus.setText("A lista não corresponde à filial deste terminal. Informe o usuário ou contate o administrador.");
                return;
            }
            operatorPicker.getItems().setAll(result.operators());
            operatorPicker.setDisable(result.operators().isEmpty());
            operatorRosterStatus.setText(result.operators().isEmpty()
                    ? "Nenhum operador disponível. Informe o usuário ou contate o administrador."
                    : result.operators().size() + " operador(es) autorizado(s) para esta filial.");
        }));
    }

    private void onCredentialRecovery(TerminalAuthSnapshot snapshot, Throwable error) {
        if (error == null) {
            if (snapshot != null && snapshot.state() == TerminalAuthState.AUTHENTICATED && runtime != null) {
                TerminalRuntime active = runtime;
                CompletableFuture.runAsync(() -> active.rotation().ifPresent(
                        br.com.lucraone.pdv.application.auth.CredentialRotationService::clearAfterVerifiedAdministrativeRecovery), worker)
                        .whenComplete((ignored, cleanupError) -> onTerminalAuth(cleanupError == null ? snapshot
                                : TerminalAuthSnapshot.of(TerminalAuthState.INCONSISTENT_STATE), null));
            } else {
                onTerminalAuth(snapshot, null);
            }
            return;
        }
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
                restoreTerminal().whenComplete((terminal, terminalError) -> Platform.runLater(() -> {
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
        showScreen(setupScreen, "Configuração do terminal");
    }

    private void goToOperatorLogin() {
        usernameField.clear();
        passwordField.clear();
        loginError.setVisible(false);
        loginError.setManaged(false);
        loginButton.setDisable(activeMachineCredential == null || authorizationDenied);
        showScreen(operatorScreen, "Terminal autorizado");
    }

    private void goToCashier() {
        logoutButton.setDisable(false);
        cashierClock.setText(DateTimeFormatter.ofPattern("EEE, dd MMM · HH:mm")
                .withZone(ZoneId.systemDefault()).format(Instant.now()));
        showScreen(cashierScreen, "Sessão de caixa aberta");
        if (authorizationMonitor != null) authorizationMonitor.checkNow();
    }

    private void showLoginError(String message) {
        loginError.setText(message);
        loginError.setVisible(true);
        loginError.setManaged(true);
    }

    private void showScreen(Node screen, String status) {
        currentScreen = screen;
        root.getChildren().setAll(screenHeader(status), screenFrame(screen), appFooter());
    }

    private VBox screenFrame(Node screen) {
        Node content = screen;
        if (screen != cashierScreen) {
            javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(screen);
            scroll.setFitToWidth(true);
            scroll.setFitToHeight(true);
            scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
            scroll.getStyleClass().add("screen-scroll");
            content = scroll;
        }
        VBox frame = new VBox(content);
        frame.setPadding(new Insets(18, 24, 18, 24));
        frame.setAlignment(Pos.CENTER);
        frame.setFillWidth(true);
        VBox.setVgrow(frame, Priority.ALWAYS);
        VBox.setVgrow(content, Priority.ALWAYS);
        return frame;
    }

    private HBox screenHeader(String status) {
        Label mark = new Label("L");
        mark.getStyleClass().add("brand-mark");
        Label brand = new Label("LucraOne");
        brand.getStyleClass().add("brand-name");
        Label product = new Label("PDV  ·  TERMINAL DE CAIXA");
        product.getStyleClass().add("brand-caption");
        VBox brandBlock = new VBox(2, brand, product);
        HBox left = new HBox(12, mark, brandBlock);
        left.setAlignment(Pos.CENTER_LEFT);
        Label contextText = new Label(currentScreen == cashierScreen || currentScreen == operatorScreen
                ? businessHeaderContext(localTerminalIdentity) : "");
        contextText.getStyleClass().add("header-context");
        contextText.setWrapText(false);
        Label statusText = new Label(status);
        statusText.getStyleClass().addAll("status-pill", status.contains("suspens") || status.contains("indispon")
                ? "status-warning" : "status-positive");
        HBox header = new HBox(left, contextText, new Region(), statusText);
        header.getStyleClass().add("app-header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(header.getChildren().get(2), Priority.ALWAYS);
        return header;
    }

    private String businessHeaderContext(LocalTerminalIdentity identity) {
        if (verifiedBusinessHeader != null && !verifiedBusinessHeader.isBlank()) return verifiedBusinessHeader;
        return operatorFacingContext(identity);
    }

    private static String operatorFacingContext(LocalTerminalIdentity identity) {
        if (identity == null) return "Terminal vinculado";
        String terminal = identity.terminalId();
        // Human-readable terminal codes (for example PDV-001) help the operator;
        // UUID-like identifiers stay in secondary diagnostic details.
        if (terminal == null || terminal.length() > 18) terminal = "Terminal vinculado";
        return terminal + "  ·  Filial vinculada";
    }

    private HBox appFooter() {
        Label left = new Label("LucraOne PDV  ·  Acesso protegido do terminal");
        Label right = new Label("Fase comercial será habilitada após a integração do catálogo");
        left.getStyleClass().add("footer-text");
        right.getStyleClass().add("footer-text");
        HBox footer = new HBox(left, new Region(), right);
        footer.getStyleClass().add("app-footer");
        footer.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(footer.getChildren().get(1), Priority.ALWAYS);
        return footer;
    }

    private static Label eyebrow(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("eyebrow");
        return label;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-heading");
        label.setWrapText(true);
        return label;
    }

    private static Label body(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("body-text");
        label.setWrapText(true);
        return label;
    }

    private static VBox infoLine(String title, Node value) {
        Label caption = eyebrow(title);
        VBox line = new VBox(5, caption, value);
        line.getStyleClass().add("info-line");
        return line;
    }

    private static HBox stepRow(String number, String title, String description) {
        Label index = new Label(number);
        index.getStyleClass().add("step-index");
        VBox copy = new VBox(4, new Label(title), body(description));
        copy.getStyleClass().add("step-copy");
        HBox row = new HBox(12, index, copy);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("step-row");
        return row;
    }

    private static Label tableHeading(String text, double width) {
        Label label = new Label(text);
        label.getStyleClass().add("table-heading");
        if (width == 1) HBox.setHgrow(label, Priority.ALWAYS);
        else label.setMinWidth(width);
        return label;
    }

    private static HBox summaryLine(String title, String value) { return summaryLine(title, value, false); }

    private static HBox summaryLine(String title, String value, boolean total) {
        Label key = new Label(title);
        Label amount = new Label(value);
        key.getStyleClass().add(total ? "summary-total-label" : "muted-text");
        amount.getStyleClass().add(total ? "summary-total-value" : "summary-value");
        HBox row = new HBox(key, new Region(), amount);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
        return row;
    }

    private static VBox summaryBlock(String title, String value) {
        return summaryBlock(title, value, false);
    }

    private static VBox summaryBlock(String title, String value, boolean total) {
        Label key = new Label(title);
        Label amount = new Label(value);
        key.getStyleClass().add(total ? "summary-total-label" : "summary-label");
        amount.getStyleClass().add(total ? "summary-total-value" : "summary-value");
        VBox block = new VBox(5, key, amount);
        if (total) block.getStyleClass().add("summary-total-block");
        return block;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void setBusy(boolean busy) {
        if (busy && runtime != null) {
            setupMessage.setText("Conectando com o servidor e validando o terminal…");
            setupConnection.setText("Verificação em andamento");
            setupConnection.getStyleClass().setAll("status-pill", "status-warning");
        }
        pairButton.setDisable(busy || runtime == null || !runtime.backendConfigured() || secureStorageUnavailable);
        validateButton.setDisable(busy || runtime == null);
        forgetButton.setDisable(busy || runtime == null);
        pairingCodeField.setDisable(busy || runtime == null || !runtime.backendConfigured() || secureStorageUnavailable);
    }

    private void scheduleExpiry(Instant instant) {
        long millis = Math.max(1, java.time.Duration.between(Instant.now(), instant).toMillis() + 1);
        expiry.setDuration(Duration.millis(millis));
        expiry.setOnFinished(event -> performTerminal(this::restoreTerminal));
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
