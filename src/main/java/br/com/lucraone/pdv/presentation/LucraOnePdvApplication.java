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
import br.com.lucraone.pdv.application.api.PdvApiException;
import br.com.lucraone.pdv.application.api.PdvFailureKind;
import br.com.lucraone.pdv.application.catalog.ActiveCatalogInfo;
import br.com.lucraone.pdv.application.catalog.CatalogProduct;
import br.com.lucraone.pdv.application.catalog.CatalogPrice;
import br.com.lucraone.pdv.application.catalog.CatalogCacheContext;
import br.com.lucraone.pdv.application.sale.ManualPaymentDeclaration;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
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
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
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
import javafx.scene.control.TableView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.collections.FXCollections;
import javafx.beans.property.ReadOnlyStringWrapper;
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
    private final Button catalogSearchButton = new Button("Pesquisar");
    private final Button catalogBarcodeButton = new Button("Código exato");
    private final Button catalogNextPageButton = new Button("Próximos resultados");
    private final Label catalogStatus = new Label("Pesquise por nome, SKU ou código de barras.");
    private final Label catalogProductDetails = new Label("Selecione um produto para consultar seus dados.");
    private final TableView<CatalogProduct> catalogResults = new TableView<>();
    private final AtomicLong catalogUiGeneration = new AtomicLong();
    private final PauseTransition catalogDebounce = new PauseTransition(Duration.millis(700));
    private volatile String catalogCurrentQuery = "";
    private volatile String catalogNextCursor;
    private final Label cashierClock = new Label();

    // ── Current sale (cart) ───────────────────────────────────────────────────
    private volatile OperationalSalesCart cart;
    private final TableView<OperationalSalesCart.CartLine> cartTable = new TableView<>();
    private final Button addToCartButton = new Button("Adicionar ao carrinho");
    private final Button removeFromCartButton = new Button("Remover item");
    private final Button finalizeSaleButton = new Button("Finalizar venda");
    private final Label saleSubtotalValue = new Label("R$ 0,00");
    private final Label saleTotalValue = new Label("R$ 0,00");
    private final Label cartStatus = new Label("Sem itens na venda.");
    private final Label catalogVersionLabel = new Label("Catálogo local: verificando…");

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
        productEntryPlaceholder.setPromptText("Pesquisar produto por nome, SKU ou código de barras");
        productEntryPlaceholder.setEditable(true);
        productEntryPlaceholder.setFocusTraversable(true);
        productEntryPlaceholder.getStyleClass().add("scan-placeholder");
        catalogSearchButton.getStyleClass().add("button-primary");
        catalogSearchButton.setOnAction(event -> searchCatalog(false));
        catalogBarcodeButton.getStyleClass().add("button-secondary");
        catalogBarcodeButton.setOnAction(event -> resolveCatalogBarcode());
        productEntryPlaceholder.setOnAction(event -> searchCatalog(false));
        productEntryPlaceholder.textProperty().addListener((observable, oldValue, newValue) -> {
            catalogDebounce.stop();
            if (isCashierScreen()) {
                catalogUiGeneration.incrementAndGet();
                catalogCurrentQuery = newValue.strip();
                catalogNextCursor = null;
                catalogResults.getItems().clear();
                catalogNextPageButton.setVisible(false);
                catalogNextPageButton.setManaged(false);
                catalogProductDetails.setText("Selecione um produto para consultar seus dados.");
                catalogNextPageButton.setDisable(true);
                catalogSearchButton.setDisable(false);
                catalogBarcodeButton.setDisable(false);
                if (!newValue.isBlank()) catalogDebounce.playFromStart();
                else catalogStatus.setText("Pesquise por nome, SKU ou código de barras.");
            }
        });
        catalogDebounce.setOnFinished(event -> searchCatalog(false));
        catalogNextPageButton.getStyleClass().add("button-secondary");
        catalogNextPageButton.setDisable(true);
        catalogNextPageButton.setVisible(false);
        catalogNextPageButton.setManaged(false);
        catalogNextPageButton.setOnAction(event -> searchCatalog(true));
        catalogStatus.setWrapText(true);
        catalogStatus.getStyleClass().add("feedback-message");
        catalogProductDetails.setWrapText(true);
        catalogProductDetails.getStyleClass().add("catalog-product-details");
        catalogResults.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        catalogResults.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        catalogResults.setPlaceholder(new Label("Nenhum produto encontrado"));
        TableColumn<CatalogProduct, String> productNameColumn = new TableColumn<>("Produto");
        productNameColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().name()));
        TableColumn<CatalogProduct, String> skuColumn = new TableColumn<>("SKU");
        skuColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(valueOrDash(cell.getValue().sku())));
        TableColumn<CatalogProduct, String> barcodeColumn = new TableColumn<>("Código");
        barcodeColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(valueOrDash(cell.getValue().barcode())));
        TableColumn<CatalogProduct, String> unitColumn = new TableColumn<>("Unidade");
        unitColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().unit()));
        TableColumn<CatalogProduct, String> priceColumn = new TableColumn<>("Preço de venda");
        priceColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(displayPrice(cell.getValue())));
        productNameColumn.setPrefWidth(300); skuColumn.setPrefWidth(110); barcodeColumn.setPrefWidth(150);
        unitColumn.setPrefWidth(85); priceColumn.setPrefWidth(160);
        catalogResults.getColumns().setAll(List.of(productNameColumn, skuColumn, barcodeColumn, unitColumn, priceColumn));
        catalogResults.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            catalogProductDetails.setText(selected == null ? "Selecione um produto para consultar seus dados."
                    : catalogDetails(selected));
        });
        catalogResults.setMinHeight(150);
        catalogResults.setPrefHeight(350);
        catalogVersionLabel.getStyleClass().add("muted-text");
        catalogVersionLabel.setWrapText(true);
        VBox productHeader = new VBox(4, eyebrow("OPERAÇÃO DO CAIXA"), cashierTitle, catalogVersionLabel);
        HBox catalogSearchRow = new HBox(10, productEntryPlaceholder, catalogSearchButton, catalogBarcodeButton);
        HBox.setHgrow(productEntryPlaceholder, Priority.ALWAYS);
        addToCartButton.getStyleClass().add("button-primary");
        addToCartButton.setMaxWidth(Double.MAX_VALUE);
        addToCartButton.disableProperty().bind(catalogResults.getSelectionModel().selectedItemProperty().isNull());
        addToCartButton.setOnAction(event -> addSelectedProductToCart());
        catalogResults.setOnMouseClicked(event -> { if (event.getClickCount() == 2) addSelectedProductToCart(); });
        VBox catalogArea = new VBox(10, eyebrow("CONSULTA DE CATÁLOGO"), catalogSearchRow,
                catalogStatus, catalogResults, catalogProductDetails, addToCartButton, catalogNextPageButton);
        catalogArea.getStyleClass().add("catalog-area");
        VBox.setVgrow(catalogResults, Priority.ALWAYS);
        VBox saleArea = new VBox(catalogArea);
        saleArea.getStyleClass().addAll("surface-card", "sale-area");
        VBox.setVgrow(catalogArea, Priority.ALWAYS);

        cartTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        cartTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        cartTable.setPlaceholder(new Label("Sem itens na venda"));
        TableColumn<OperationalSalesCart.CartLine, String> cartProductColumn = new TableColumn<>("Produto");
        cartProductColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().product().name()));
        TableColumn<OperationalSalesCart.CartLine, String> cartQuantityColumn = new TableColumn<>("Qtd");
        cartQuantityColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(plainQuantity(cell.getValue().quantity())));
        TableColumn<OperationalSalesCart.CartLine, String> cartUnitColumn = new TableColumn<>("Unit.");
        cartUnitColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatBrl(cell.getValue().unitPrice())));
        TableColumn<OperationalSalesCart.CartLine, String> cartLineTotalColumn = new TableColumn<>("Total");
        cartLineTotalColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatBrl(cell.getValue().lineTotal())));
        cartProductColumn.setPrefWidth(170); cartQuantityColumn.setPrefWidth(55);
        cartUnitColumn.setPrefWidth(85); cartLineTotalColumn.setPrefWidth(95);
        cartTable.getColumns().setAll(List.of(cartProductColumn, cartQuantityColumn, cartUnitColumn, cartLineTotalColumn));
        cartTable.setMinHeight(160);
        VBox.setVgrow(cartTable, Priority.ALWAYS);

        Button quantityMinusButton = new Button("−");
        Button quantityPlusButton = new Button("+");
        quantityMinusButton.getStyleClass().add("button-secondary");
        quantityPlusButton.getStyleClass().add("button-secondary");
        removeFromCartButton.getStyleClass().add("button-quiet");
        quantityMinusButton.disableProperty().bind(cartTable.getSelectionModel().selectedItemProperty().isNull());
        quantityPlusButton.disableProperty().bind(cartTable.getSelectionModel().selectedItemProperty().isNull());
        removeFromCartButton.disableProperty().bind(cartTable.getSelectionModel().selectedItemProperty().isNull());
        quantityMinusButton.setOnAction(event -> adjustSelectedCartQuantity(-1));
        quantityPlusButton.setOnAction(event -> adjustSelectedCartQuantity(1));
        removeFromCartButton.setOnAction(event -> removeSelectedCartLine());
        Region cartActionsSpacer = new Region();
        HBox cartActions = new HBox(8, quantityMinusButton, quantityPlusButton, cartActionsSpacer, removeFromCartButton);
        cartActions.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(cartActionsSpacer, Priority.ALWAYS);

        saleSubtotalValue.getStyleClass().add("summary-value");
        saleTotalValue.getStyleClass().add("summary-total-value");
        cartStatus.setWrapText(true);
        cartStatus.getStyleClass().add("muted-text");
        finalizeSaleButton.getStyleClass().add("button-primary");
        finalizeSaleButton.setMaxWidth(Double.MAX_VALUE);
        finalizeSaleButton.setDisable(true);
        finalizeSaleButton.setOnAction(event -> finalizeSale());
        VBox saleSummary = new VBox(14,
                eyebrow("VENDA ATUAL"),
                cartTable,
                cartActions,
                new javafx.scene.control.Separator(),
                summaryRow("SUBTOTAL", saleSubtotalValue, false),
                summaryRow("TOTAL DA VENDA", saleTotalValue, true),
                finalizeSaleButton,
                cartStatus);
        saleSummary.getStyleClass().addAll("surface-card", "sale-summary");
        HBox.setHgrow(saleArea, Priority.ALWAYS);
        saleArea.setMaxWidth(Double.MAX_VALUE);
        saleSummary.setMinWidth(340);
        saleSummary.setPrefWidth(420);
        saleSummary.setMaxWidth(520);
        HBox cashierBody = new HBox(18, saleArea, saleSummary);
        cashierBody.setAlignment(Pos.CENTER);
        VBox cashierMain = new VBox(12, productHeader, cashierBody);
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
                runtime.heartbeat().ifPresent(service -> service.setAuthorized(false));
                showSuspended("Não foi possível confirmar a autorização com o servidor. Tente novamente quando a conexão estiver disponível.", false);
                return;
            }
            secureStorageUnavailable = snapshot.state() == TerminalAuthState.SECURE_STORE_UNAVAILABLE;
            lastAuthorizationCheck = Instant.now();
            boolean authenticated = snapshot.state() == TerminalAuthState.AUTHENTICATED;
            terminalAuthorizationValid = authenticated;
            runtime.heartbeat().ifPresent(service -> service.setAuthorized(authenticated));

            if (authenticated) {
                authorizationDenied = false;
                snapshot.expiresAt().ifPresent(this::scheduleExpiry);
                snapshot.expiresAt().ifPresent(exp -> runtime.renewal().ifPresent(r -> r.scheduleFor(exp)));
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
            runtime.heartbeat().ifPresent(service -> service.setAuthorized(true));
            synchronizeCatalogWhenOptedIn();
            snapshot.expiresAt().ifPresent(exp -> runtime.renewal().ifPresent(r -> r.scheduleFor(exp)));
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
        runtime.heartbeat().ifPresent(service -> service.setAuthorized(false));
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
        catalogDebounce.stop();
        catalogUiGeneration.incrementAndGet();
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
                synchronizeCatalogWhenOptedIn();
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

    /** Distribution is opt-in so a regular installation never contacts the delivery API by default. */
    private void synchronizeCatalogWhenOptedIn() {
        if (!Boolean.getBoolean("lucraone.pdv.catalog-distribution.enabled")) return;
        TerminalRuntime active = runtime;
        MachineCredential credential = activeMachineCredential;
        if (active == null || credential == null) return;
        active.authentication().lastVerifiedContext().ifPresent(context -> active.catalogDistribution().ifPresent(service ->
                service.synchronize(credential, CatalogCacheContext.from(context)).whenComplete((report, error) -> {
                    if (error == null) System.getLogger(LucraOnePdvApplication.class.getName()).log(System.Logger.Level.INFO,
                            "Catalog sync complete: discovered={0}, applied={1}, recovered={2}",
                            report.discovered(), report.applied(), report.recoveredAcknowledgements());
                    else System.getLogger(LucraOnePdvApplication.class.getName()).log(System.Logger.Level.WARNING,
                            "Catalog sync did not complete; prior active catalog remains available.");
                })));
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
        TerminalRuntime active = runtime;
        cart = active == null ? null : active.sale().map(OperationalSalesCart::new).orElse(null);
        cartStatus.setText(cart == null
                ? "Vendas indisponíveis nesta instalação."
                : "Sem itens na venda.");
        refreshCartUi();
        updateCatalogVersionIndicator();
        showScreen(cashierScreen, "Sessão de caixa aberta");
        if (authorizationMonitor != null) authorizationMonitor.checkNow();
    }

    // ── Cart (current sale) ────────────────────────────────────────────────────

    private void addSelectedProductToCart() {
        if (cart == null) return;
        CatalogProduct selected = catalogResults.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        try {
            cart.addUnit(selected);
            cartStatus.setText(selected.name() + " adicionado à venda.");
        } catch (RuntimeException error) {
            cartStatus.setText("Não foi possível adicionar o produto: " + error.getMessage());
        }
        refreshCartUi();
    }

    private void adjustSelectedCartQuantity(int delta) {
        if (cart == null) return;
        OperationalSalesCart.CartLine line = cartTable.getSelectionModel().getSelectedItem();
        if (line == null) return;
        String productId = line.product().id();
        BigDecimal next = line.quantity().stripTrailingZeros().add(BigDecimal.valueOf(delta));
        try {
            if (next.signum() <= 0) cart.remove(productId);
            else cart.setQuantity(productId, next);
            cartStatus.setText("Venda atualizada.");
        } catch (RuntimeException error) {
            cartStatus.setText("Não foi possível alterar a quantidade: " + error.getMessage());
        }
        refreshCartUi();
        reselectCartLine(productId);
    }

    private void removeSelectedCartLine() {
        if (cart == null) return;
        OperationalSalesCart.CartLine line = cartTable.getSelectionModel().getSelectedItem();
        if (line == null) return;
        cart.remove(line.product().id());
        cartStatus.setText("Item removido da venda.");
        refreshCartUi();
    }

    private void reselectCartLine(String productId) {
        for (OperationalSalesCart.CartLine line : cartTable.getItems()) {
            if (line.product().id().equals(productId)) {
                cartTable.getSelectionModel().select(line);
                return;
            }
        }
    }

    private void refreshCartUi() {
        if (cart == null) {
            cartTable.getItems().clear();
            saleSubtotalValue.setText("R$ 0,00");
            saleTotalValue.setText("R$ 0,00");
            finalizeSaleButton.setDisable(true);
            return;
        }
        cartTable.setItems(FXCollections.observableArrayList(cart.lines()));
        saleSubtotalValue.setText(formatBrl(cart.subtotal()));
        saleTotalValue.setText(formatBrl(cart.total()));
        boolean empty = cart.isEmpty();
        finalizeSaleButton.setDisable(empty);
        if (empty) cartStatus.setText("Sem itens na venda.");
    }

    private void finalizeSale() {
        OperationalSalesCart active = cart;
        if (active == null || active.isEmpty()) return;
        Optional<PaymentChoice> choice = askPayment(active.total());
        if (choice.isEmpty()) return;
        PaymentChoice payment = choice.orElseThrow();
        finalizeSaleButton.setDisable(true);
        cartStatus.setText("Registrando a venda localmente…");
        CompletableFuture.supplyAsync(
                () -> active.checkout(payment.method(), payment.amount(), payment.declaration()), worker)
                .whenComplete((sale, error) -> Platform.runLater(() -> onSaleFinalized(sale, error)));
    }

    private void onSaleFinalized(Sale sale, Throwable error) {
        if (stopped || currentScreen != cashierScreen) return;
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            refreshCartUi(); // checkout failed: the cart is preserved for a retry
            cartStatus.setText("A venda não foi concluída (" + cause.getMessage()
                    + "). Os itens foram mantidos para nova tentativa.");
            return;
        }
        refreshCartUi(); // checkout cleared the cart after a successful persistence
        String change = sale.payment().changeAmount().isZero()
                ? "" : "  ·  Troco " + formatBrl(sale.payment().changeAmount());
        cartStatus.setText("Venda registrada localmente: " + formatBrl(sale.totalAmount()) + change
                + "  ·  nº " + sale.saleId().toString().substring(0, 8));
    }

    private Optional<PaymentChoice> askPayment(Money total) {
        Dialog<PaymentChoice> dialog = new Dialog<>();
        dialog.setTitle("Finalizar venda");
        dialog.setHeaderText("Total a receber: " + formatBrl(total));
        ButtonType confirmType = new ButtonType("Confirmar pagamento", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmType, ButtonType.CANCEL);

        ComboBox<PaymentMethod> method = new ComboBox<>();
        method.getItems().setAll(PaymentMethod.CASH, PaymentMethod.CARD_DEBIT, PaymentMethod.CARD_CREDIT,
                PaymentMethod.PIX, PaymentMethod.MEAL_VOUCHER, PaymentMethod.FOOD_VOUCHER);
        method.setValue(PaymentMethod.CASH);
        method.setMaxWidth(Double.MAX_VALUE);
        method.setButtonCell(paymentMethodCell());
        method.setCellFactory(list -> paymentMethodCell());
        TextField tendered = new TextField(total.toPlainString());
        Label tenderedLabel = new Label("Valor recebido (dinheiro)");
        CheckBox confirmation = new CheckBox();
        confirmation.setWrapText(true);
        Label methodHint = new Label();
        methodHint.setWrapText(true);
        methodHint.getStyleClass().add("muted-text");

        Runnable syncMethod = () -> {
            PaymentMethod selected = method.getValue();
            boolean cash = selected == PaymentMethod.CASH;
            tendered.setDisable(!cash);
            tenderedLabel.setDisable(!cash);
            confirmation.setText(switch (selected) {
                case CASH -> "Confirmo o recebimento em dinheiro.";
                case CARD_DEBIT, CARD_CREDIT -> "Confirmo que o cartão foi aprovado na maquininha (confirmação externa).";
                case PIX -> "Confirmo que verifiquei o Pix recebido no canal oficial.";
                case MEAL_VOUCHER, FOOD_VOUCHER ->
                        "Confirmo que o ticket foi aprovado no equipamento externo (confirmação manual).";
                case OTHER -> "";
            });
            methodHint.setText(cash ? "O troco é calculado a partir do valor recebido."
                    : "Pagamento manual: a confirmação pressupõe verificação externa feita pelo operador.");
        };

        VBox content = new VBox(10, new Label("Forma de pagamento"), method,
                tenderedLabel, tendered, confirmation, methodHint);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);

        Node confirmButton = dialog.getDialogPane().lookupButton(confirmType);
        Runnable validate = () -> {
            boolean ok = confirmation.isSelected();
            if (method.getValue() == PaymentMethod.CASH) {
                try {
                    ok = ok && new BigDecimal(tendered.getText().strip()).compareTo(total.amount()) >= 0;
                } catch (RuntimeException invalid) {
                    ok = false;
                }
            }
            confirmButton.setDisable(!ok);
        };
        method.valueProperty().addListener((observable, oldValue, newValue) -> { syncMethod.run(); validate.run(); });
        confirmation.selectedProperty().addListener((observable, oldValue, newValue) -> validate.run());
        tendered.textProperty().addListener((observable, oldValue, newValue) -> validate.run());
        syncMethod.run();
        validate.run();

        dialog.setResultConverter(button -> {
            if (button != confirmType) return null;
            PaymentMethod selected = method.getValue();
            String amount = selected == PaymentMethod.CASH ? tendered.getText().strip() : total.toPlainString();
            ManualPaymentDeclaration declaration = switch (selected) {
                case CASH -> ManualPaymentDeclaration.CASH_RECEIVED;
                case CARD_DEBIT, CARD_CREDIT -> ManualPaymentDeclaration.EXTERNAL_CARD_APPROVED;
                case PIX -> ManualPaymentDeclaration.PIX_OFFICIAL_CHANNEL_CHECKED;
                case MEAL_VOUCHER, FOOD_VOUCHER -> ManualPaymentDeclaration.EXTERNAL_VOUCHER_APPROVED;
                case OTHER -> null;
            };
            return new PaymentChoice(selected, amount, declaration);
        });
        return dialog.showAndWait();
    }

    private static javafx.scene.control.ListCell<PaymentMethod> paymentMethodCell() {
        return new javafx.scene.control.ListCell<>() {
            @Override protected void updateItem(PaymentMethod item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : paymentMethodLabel(item));
            }
        };
    }

    private static String paymentMethodLabel(PaymentMethod method) {
        return switch (method) {
            case CASH -> "Dinheiro";
            case CARD_DEBIT -> "Cartão de débito (manual)";
            case CARD_CREDIT -> "Cartão de crédito (manual)";
            case PIX -> "Pix (manual)";
            case MEAL_VOUCHER -> "Ticket refeição (manual)";
            case FOOD_VOUCHER -> "Ticket alimentação (manual)";
            case OTHER -> "Outro";
        };
    }

    private void updateCatalogVersionIndicator() {
        catalogVersionLabel.setText("Catálogo local: verificando…");
        TerminalRuntime active = runtime;
        if (active == null || active.catalog().isEmpty()) {
            catalogVersionLabel.setText("Catálogo local indisponível.");
            return;
        }
        active.catalog().orElseThrow().activeCatalogInfo().whenComplete((info, error) -> Platform.runLater(() -> {
            if (stopped || currentScreen != cashierScreen) return;
            if (error != null) {
                catalogVersionLabel.setText("Catálogo local ativo — não foi possível ler a versão.");
                return;
            }
            if (info == null || info.isEmpty()) {
                catalogVersionLabel.setText("Catálogo local ainda não sincronizado para este terminal.");
                return;
            }
            ActiveCatalogInfo value = info.orElseThrow();
            String when = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.systemDefault()).format(value.activatedAt());
            catalogVersionLabel.setText("Catálogo local: versão " + value.version() + "  ·  "
                    + value.productCount() + " produto(s)  ·  ativado em " + when);
        }));
    }

    private record PaymentChoice(PaymentMethod method, String amount, ManualPaymentDeclaration declaration) { }

    private void searchCatalog(boolean nextPage) {
        catalogDebounce.stop();
        if (stopped || currentScreen != cashierScreen || !terminalAuthorizationValid || runtime == null
                || runtime.catalog().isEmpty()) return;
        String query = nextPage ? catalogCurrentQuery : productEntryPlaceholder.getText().strip();
        if (query.isBlank()) {
            catalogStatus.setText("Informe nome, SKU ou código de barras para pesquisar.");
            return;
        }
        if (query.length() > 100) {
            catalogStatus.setText("A pesquisa pode ter até 100 caracteres.");
            return;
        }
        if (!nextPage) {
            catalogCurrentQuery = query;
            catalogNextCursor = null;
            catalogNextPageButton.setVisible(false);
            catalogNextPageButton.setManaged(false);
            catalogResults.getItems().clear();
            catalogProductDetails.setText("Selecione um produto para consultar seus dados.");
        }
        long generation = catalogUiGeneration.incrementAndGet();
        catalogStatus.setText("Consultando catálogo local…");
        catalogSearchButton.setDisable(true);
        catalogBarcodeButton.setDisable(true);
        catalogNextPageButton.setDisable(true);
        String cursor = nextPage ? catalogNextCursor : null;
        runtime.catalog().orElseThrow().search(query, 50, cursor).whenComplete((page, error) -> Platform.runLater(() -> {
            if (!catalogRequestCurrent(generation)) return;
            catalogSearchButton.setDisable(false);
            catalogBarcodeButton.setDisable(false);
            if (error != null) { showCatalogFailure(error, generation); return; }
            if (nextPage) catalogResults.getItems().addAll(page.products());
            else catalogResults.setItems(FXCollections.observableArrayList(page.products()));
            catalogNextCursor = page.nextCursor();
            catalogNextPageButton.setDisable(!page.hasMore());
            catalogNextPageButton.setVisible(page.hasMore());
            catalogNextPageButton.setManaged(page.hasMore());
            catalogStatus.setText(page.products().isEmpty() && !nextPage
                    ? "Nenhum produto encontrado na versão local ativa deste catálogo."
                    : page.products().size() + " produto(s) encontrados na versão local ativa.");
        }));
    }

    private void resolveCatalogBarcode() {
        catalogDebounce.stop();
        String barcode = productEntryPlaceholder.getText().strip();
        if (stopped || currentScreen != cashierScreen || !terminalAuthorizationValid || runtime == null
                || runtime.catalog().isEmpty()) return;
        if (barcode.isEmpty() || barcode.length() > 14) {
            catalogStatus.setText("Informe um código de barras com até 14 caracteres.");
            return;
        }
        catalogCurrentQuery = barcode;
        catalogNextCursor = null;
        catalogNextPageButton.setVisible(false);
        catalogNextPageButton.setManaged(false);
        long generation = catalogUiGeneration.incrementAndGet();
        catalogResults.getItems().clear();
        catalogProductDetails.setText("Selecione um produto para consultar seus dados.");
        catalogStatus.setText("Lendo código no catálogo local…");
        catalogSearchButton.setDisable(true);
        catalogBarcodeButton.setDisable(true);
        catalogNextPageButton.setDisable(true);
        runtime.catalog().orElseThrow().resolveBarcode(barcode).whenComplete((result, error) -> Platform.runLater(() -> {
            if (!catalogRequestCurrent(generation)) return;
            catalogSearchButton.setDisable(false);
            catalogBarcodeButton.setDisable(false);
            if (error != null) { showCatalogFailure(error, generation); return; }
            catalogResults.setItems(FXCollections.observableArrayList(result.product()));
            catalogResults.getSelectionModel().selectFirst();
            catalogProductDetails.setText(catalogDetails(result.product()) + (result.commercialPackage() == null
                    ? "   |   Código do produto · quantidade base " + result.quantity().toPlainString() + " " + result.unit()
                    : "   |   Embalagem: " + result.commercialPackage().name() + " · quantidade base "
                            + result.quantity().toPlainString() + " " + result.unit()));
            catalogStatus.setText("Produto encontrado por código de barras exato.");
        }));
    }

    private boolean catalogRequestCurrent(long generation) {
        return !stopped && currentScreen == cashierScreen && terminalAuthorizationValid
                && catalogUiGeneration.get() == generation;
    }

    private boolean isCashierScreen() { return cashierScreen != null && currentScreen == cashierScreen; }

    private void showCatalogFailure(Throwable error, long generation) {
        if (catalogUiGeneration.get() != generation || stopped) return;
        catalogSearchButton.setDisable(false);
        catalogBarcodeButton.setDisable(false);
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        PdvFailureKind kind = cause instanceof PdvApiException api ? api.kind() : null;
        if (kind == PdvFailureKind.PRODUCT_NOT_FOUND) {
            catalogResults.getItems().clear();
            catalogNextPageButton.setDisable(true);
            catalogNextPageButton.setVisible(false);
            catalogNextPageButton.setManaged(false);
            catalogStatus.setText("Nenhum produto encontrado para este código de barras.");
        } else if (kind == PdvFailureKind.BARCODE_CONFLICT) {
            catalogStatus.setText("Este código de barras está associado a mais de um produto. Procure o administrador.");
        } else if (kind == PdvFailureKind.UNAUTHENTICATED || kind == PdvFailureKind.OPERATOR_SESSION_INVALID) {
            catalogUiGeneration.incrementAndGet();
            runtime.operator().ifPresent(service -> service.invalidateLocal());
            catalogStatus.setText("A sessão expirou. Entre novamente para consultar o catálogo.");
            restoreTerminal().whenComplete(this::onTerminalAuth);
        } else if (kind == PdvFailureKind.FORBIDDEN) {
            catalogStatus.setText("Consulta de catálogo indisponível. A credencial deste terminal pode precisar de atualização.");
            if (authorizationMonitor != null) authorizationMonitor.checkNow();
        } else if (kind == PdvFailureKind.NETWORK_UNAVAILABLE || kind == PdvFailureKind.TIMEOUT) {
            catalogStatus.setText("Catálogo temporariamente indisponível. Verifique a conexão e tente novamente.");
        } else if (kind == PdvFailureKind.RATE_LIMITED) {
            catalogStatus.setText("Muitas consultas em sequência. Aguarde um momento antes de pesquisar novamente.");
        } else if (kind == PdvFailureKind.SERVER_ERROR) {
            catalogStatus.setText("Não foi possível ler o catálogo local. Verifique o armazenamento do terminal.");
        } else if (kind == PdvFailureKind.INVALID_RESPONSE || kind == PdvFailureKind.PROTOCOL_ERROR) {
            catalogStatus.setText("O registro local do catálogo está inválido. Atualize a carga ou procure o administrador.");
        } else {
            catalogStatus.setText("Não foi possível consultar o catálogo. Verifique a autorização do terminal e tente novamente.");
        }
    }

    private static String valueOrDash(String value) { return value == null || value.isBlank() ? "—" : value; }

    private static String displayPrice(CatalogProduct product) {
        List<CatalogPrice> brl = product.salePrices().stream().filter(price -> "BRL".equals(price.currency())).toList();
        if (brl.size() == 1 && product.salePrices().size() == 1) return brazilianCurrency(brl.get(0));
        if (brl.size() == 1) return "Múltiplas moedas";
        if (brl.size() > 1) return "Preço em conflito";
        return product.salePrices().isEmpty() ? "Preço não cadastrado" : "Ver outras moedas";
    }

    private static String catalogDetails(CatalogProduct product) {
        String prices = product.salePrices().isEmpty() ? "Preço não cadastrado" : product.salePrices().stream()
                .map(price -> "BRL".equals(price.currency()) ? brazilianCurrency(price)
                        : price.currency() + " " + price.amount().toPlainString())
                .collect(java.util.stream.Collectors.joining("  ·  "));
        return product.name() + "   |   SKU: " + valueOrDash(product.sku()) + "   |   Código: "
                + valueOrDash(product.barcode()) + "   |   Unidade: " + product.unit() + "   |   " + prices;
    }

    private static String brazilianCurrency(CatalogPrice price) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
        format.setCurrency(java.util.Currency.getInstance("BRL"));
        return format.format(price.amount());
    }

    private static String formatBrl(Money money) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
        format.setCurrency(java.util.Currency.getInstance("BRL"));
        return format.format(money.amount());
    }

    private static String plainQuantity(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }

    private static HBox summaryRow(String title, Label value, boolean total) {
        Label key = new Label(title);
        key.getStyleClass().add(total ? "summary-total-label" : "muted-text");
        Region spacer = new Region();
        HBox row = new HBox(key, spacer, value);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return row;
    }

    private void showLoginError(String message) {
        loginError.setText(message);
        loginError.setVisible(true);
        loginError.setManaged(true);
    }

    private void showScreen(Node screen, String status) {
        if (screen != cashierScreen) {
            catalogDebounce.stop();
            catalogUiGeneration.incrementAndGet();
            productEntryPlaceholder.clear();
            catalogSearchButton.setDisable(false);
            catalogBarcodeButton.setDisable(false);
            catalogNextPageButton.setDisable(true);
            catalogResults.getItems().clear();
            catalogProductDetails.setText("Selecione um produto para consultar seus dados.");
            catalogCurrentQuery = "";
            catalogNextCursor = null;
            catalogNextPageButton.setVisible(false);
            catalogNextPageButton.setManaged(false);
            cart = null;
            refreshCartUi();
            cartStatus.setText("Sem itens na venda.");
        }
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
