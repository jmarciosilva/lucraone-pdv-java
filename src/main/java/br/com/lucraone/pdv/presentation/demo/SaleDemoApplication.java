package br.com.lucraone.pdv.presentation.demo;

import br.com.lucraone.pdv.application.catalog.CatalogProduct;
import br.com.lucraone.pdv.application.sale.ManualPaymentDeclaration;
import br.com.lucraone.pdv.domain.sale.Money;
import br.com.lucraone.pdv.domain.sale.PaymentMethod;
import br.com.lucraone.pdv.domain.sale.Sale;
import br.com.lucraone.pdv.infrastructure.demo.DemoSalesEnvironment;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/** Separate demonstration entry point. It does not initialize the production terminal runtime. */
public final class SaleDemoApplication extends Application {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lucraone-demo-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private DemoSalesEnvironment environment;
    private DemoSalesCart cart;
    private final TextField productCode = new TextField();
    private final ListView<CatalogProduct> results = new ListView<>();
    private final TableView<DemoSalesCart.CartLine> cartTable = new TableView<>();
    private final ComboBox<PaymentChoice> paymentMethod = new ComboBox<>();
    private final TextField amountReceived = new TextField();
    private final Label subtotalValue = new Label("R$ 0,00");
    private final Label discountValue = new Label("R$ 0,00");
    private final Label totalValue = new Label("R$ 0,00");
    private final Label changeValue = new Label("R$ 0,00");
    private final Label status = new Label("Catálogo DEMO carregado.");
    private final Button searchButton = new Button("Buscar produto");
    private final Button addButton = new Button("Adicionar selecionado");
    private final Button incrementButton = new Button("+ Quantidade");
    private final Button decrementButton = new Button("− Quantidade");
    private final Button removeButton = new Button("Remover item");
    private final Button confirmButton = new Button("Confirmar pagamento manual");

    @Override public void start(Stage stage) {
        try {
            environment = DemoSalesEnvironment.create();
            cart = new DemoSalesCart(environment);
        } catch (IOException | RuntimeException failure) {
            showError("Não foi possível iniciar o ambiente DEMO", failure.getMessage());
            Platform.exit();
            return;
        }
        BorderPane page = buildScreen();
        Scene scene = new Scene(page, 1180, 760);
        var css = getClass().getResource("/br/com/lucraone/pdv/presentation/application.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        scene.getStylesheets().add(getClass().getResource("/br/com/lucraone/pdv/presentation/demo/sales-demo.css").toExternalForm());
        stage.setTitle("LucraOne PDV — Caixa DEMO");
        stage.setMinWidth(980);
        stage.setMinHeight(650);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> closeDemo());
        stage.show();
    }

    private BorderPane buildScreen() {
        Label brand = new Label("LucraOne  /  PDV");
        brand.getStyleClass().add("demo-brand");
        Label demo = new Label("AMBIENTE DEMO · DADOS FICTÍCIOS · SEM CONEXÃO COM SERVIDOR");
        demo.getStyleClass().add("demo-banner");
        VBox header = new VBox(10, brand, demo);
        header.getStyleClass().add("demo-header");

        productCode.setPromptText("Leia ou digite SKU, código de barras ou nome");
        productCode.setOnAction(event -> searchProducts());
        searchButton.setOnAction(event -> searchProducts());
        addButton.setOnAction(event -> addSelected());
        results.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(CatalogProduct product, boolean empty) {
                super.updateItem(product, empty);
                setText(empty || product == null ? null : product.name() + "   ·   " + product.sku()
                        + "   ·   " + product.barcode() + "   ·   " + formatPrice(product));
            }
        });
        results.setOnMouseClicked(event -> { if (event.getClickCount() == 2) addSelected(); });

        VBox lookup = new VBox(12, title("Produtos fictícios"),
                new HBox(8, productCode, searchButton), results, addButton);
        HBox.setHgrow(productCode, Priority.ALWAYS);
        VBox.setVgrow(results, Priority.ALWAYS);
        lookup.getStyleClass().add("demo-card");

        configureCartTable();
        HBox cartActions = new HBox(8, incrementButton, decrementButton, removeButton);
        incrementButton.setOnAction(event -> adjustSelected(BigDecimal.ONE));
        decrementButton.setOnAction(event -> adjustSelected(BigDecimal.ONE.negate()));
        removeButton.setOnAction(event -> removeSelected());
        VBox cartBox = new VBox(12, title("Venda atual"), cartTable, cartActions);
        VBox.setVgrow(cartTable, Priority.ALWAYS);
        cartBox.getStyleClass().add("demo-card");

        VBox left = new VBox(16, lookup, cartBox);
        left.setPadding(new Insets(18));
        VBox.setVgrow(lookup, Priority.ALWAYS);
        VBox.setVgrow(cartBox, Priority.ALWAYS);

        VBox payment = buildPaymentPanel();
        BorderPane body = new BorderPane();
        body.setTop(header);
        body.setCenter(left);
        body.setRight(payment);
        status.getStyleClass().add("demo-status");
        VBox footer = new VBox(status);
        footer.setPadding(new Insets(0, 22, 16, 22));
        body.setBottom(footer);
        body.getStyleClass().add("demo-page");
        BorderPane.setMargin(payment, new Insets(18, 18, 18, 0));
        return body;
    }

    private VBox buildPaymentPanel() {
        paymentMethod.setItems(FXCollections.observableArrayList(PaymentChoice.values()));
        paymentMethod.setConverter(new StringConverter<>() {
            @Override public String toString(PaymentChoice choice) { return choice == null ? "" : choice.label; }
            @Override public PaymentChoice fromString(String value) { return null; }
        });
        paymentMethod.getSelectionModel().select(PaymentChoice.CASH);
        paymentMethod.valueProperty().addListener((observable, oldValue, choice) -> updatePaymentControls());
        amountReceived.setPromptText("Valor recebido");
        amountReceived.textProperty().addListener((observable, oldValue, value) -> updateSummary());
        confirmButton.getStyleClass().add("button-primary");
        confirmButton.setMaxWidth(Double.MAX_VALUE);
        confirmButton.setOnAction(event -> requestManualConfirmation());
        Label manualNote = new Label("Cartão e Pix são apenas declarações do operador nesta demonstração."
                + " Nenhum provedor, banco ou adquirente é consultado. Descontos estão desativados no DEMO.");
        manualNote.setWrapText(true);
        manualNote.getStyleClass().add("demo-note");
        VBox totals = new VBox(12,
                summaryRow("Subtotal", subtotalValue),
                summaryRow("Descontos", discountValue),
                new Separator(), summaryRow("Total", totalValue),
                summaryRow("Troco", changeValue));
        VBox panel = new VBox(16, title("Pagamento"), paymentMethod,
                new Label("Valor recebido"), amountReceived, totals, manualNote, confirmButton);
        panel.setPrefWidth(310);
        panel.setPadding(new Insets(20));
        panel.getStyleClass().add("demo-card");
        updatePaymentControls();
        return panel;
    }

    private void configureCartTable() {
        cartTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<DemoSalesCart.CartLine, String> name = new TableColumn<>("Produto");
        name.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(cell.getValue().product().name()));
        TableColumn<DemoSalesCart.CartLine, String> unit = new TableColumn<>("Preço un.");
        unit.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(formatPrice(cell.getValue().product())));
        TableColumn<DemoSalesCart.CartLine, String> quantity = new TableColumn<>("Qtd.");
        quantity.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(cell.getValue().quantity().stripTrailingZeros().toPlainString()));
        TableColumn<DemoSalesCart.CartLine, String> lineTotal = new TableColumn<>("Total");
        lineTotal.setCellValueFactory(cell -> new javafx.beans.property.ReadOnlyStringWrapper(
                formatMoney(new Money(formatPriceAmount(cell.getValue().product()), "BRL")
                        .multiplyByQuantity(cell.getValue().quantity()))));
        cartTable.getColumns().setAll(List.of(name, unit, quantity, lineTotal));
        cartTable.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.SINGLE);
    }

    private VBox summaryRow(String caption, Label value) {
        HBox full = new HBox(new Label(caption), new Region(), value);
        full.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(full.getChildren().get(1), Priority.ALWAYS);
        return new VBox(full);
    }

    private static Label title(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("demo-title");
        return label;
    }

    private void searchProducts() {
        String query = productCode.getText().strip();
        if (query.isEmpty()) return;
        setBusy(true);
        worker.execute(() -> {
            try {
                List<CatalogProduct> found = cart.search(query);
                Platform.runLater(() -> {
                    results.setItems(FXCollections.observableArrayList(found));
                    if (!found.isEmpty()) results.getSelectionModel().selectFirst();
                    status.setText(found.size() + " produto(s) encontrados no catálogo DEMO local.");
                    setBusy(false);
                });
            } catch (RuntimeException failure) {
                Platform.runLater(() -> { status.setText(failure.getMessage()); setBusy(false); });
            }
        });
    }

    private void addSelected() {
        CatalogProduct selected = results.getSelectionModel().getSelectedItem();
        if (selected == null) { status.setText("Selecione um produto da busca."); return; }
        setBusy(true);
        worker.execute(() -> {
            try {
                cart.add(selected.id());
                Platform.runLater(() -> { refreshCart(); status.setText(selected.name() + " adicionado ao carrinho DEMO."); setBusy(false); });
            } catch (RuntimeException failure) { Platform.runLater(() -> { status.setText(failure.getMessage()); setBusy(false); }); }
        });
    }

    private void adjustSelected(BigDecimal delta) {
        DemoSalesCart.CartLine selected = cartTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        BigDecimal quantity = selected.quantity().add(delta);
        try {
            if (quantity.signum() <= 0) cart.remove(selected.product().id());
            else cart.setQuantity(selected.product().id(), quantity);
            refreshCart();
        } catch (RuntimeException failure) { status.setText(failure.getMessage()); }
    }

    private void removeSelected() {
        DemoSalesCart.CartLine selected = cartTable.getSelectionModel().getSelectedItem();
        if (selected != null) { cart.remove(selected.product().id()); refreshCart(); }
    }

    private void refreshCart() {
        cartTable.setItems(FXCollections.observableArrayList(cart.lines()));
        updateSummary();
    }

    private void updateSummary() {
        if (cart == null) return;
        Money total = cart.total();
        subtotalValue.setText(formatMoney(total));
        discountValue.setText(formatMoney(Money.zero("BRL")));
        totalValue.setText(formatMoney(total));
        try { changeValue.setText(formatMoney(cart.changeFor(amountReceived.getText().replace(',', '.').isBlank()
                ? "0" : amountReceived.getText().replace(',', '.')))); }
        catch (NumberFormatException invalidAmount) { changeValue.setText("—"); }
        catch (IllegalArgumentException insufficient) { changeValue.setText("Insuficiente"); }
        if (paymentMethod.getValue() != null && paymentMethod.getValue() != PaymentChoice.CASH)
            amountReceived.setText(total.amount().toPlainString());
    }

    private void updatePaymentControls() {
        boolean cash = paymentMethod.getValue() == PaymentChoice.CASH;
        amountReceived.setDisable(!cash);
        amountReceived.setEditable(cash);
        if (!cash && cart != null) amountReceived.setText(cart.total().amount().toPlainString());
        if (cash && amountReceived.getText().isBlank()) amountReceived.setText("0,00");
        updateSummary();
    }

    private void requestManualConfirmation() {
        if (cart.isEmpty()) { status.setText("Adicione ao menos um produto antes de confirmar."); return; }
        PaymentChoice choice = paymentMethod.getValue();
        ManualPaymentDeclaration declaration = switch (choice) {
            case CASH -> ManualPaymentDeclaration.CASH_RECEIVED;
            case CARD_DEBIT, CARD_CREDIT -> ManualPaymentDeclaration.EXTERNAL_CARD_APPROVED;
            case PIX -> ManualPaymentDeclaration.PIX_OFFICIAL_CHANNEL_CHECKED;
        };
        String assertion = switch (choice) {
            case CASH -> "Confirme o valor recebido em dinheiro e registre esta venda de demonstração.";
            case CARD_DEBIT, CARD_CREDIT -> "Declaro que a maquininha externa informou aprovação. "
                    + "O LucraOne DEMO não consulta nem simula adquirentes.";
            case PIX -> "Declaro que conferi o recebimento no canal oficial do estabelecimento. "
                    + "Um comprovante visual não é validado automaticamente.";
        };
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirmação manual · DEMO");
        confirmation.setHeaderText("Ação explícita do operador");
        confirmation.setContentText(assertion);
        if (confirmation.showAndWait().filter(button -> button == javafx.scene.control.ButtonType.OK).isEmpty()) return;
        setBusy(true);
        worker.execute(() -> {
            try {
                Sale sale = cart.confirm(choice.method, amountReceived.getText().replace(',', '.'), declaration);
                Platform.runLater(() -> {
                    refreshCart();
                    amountReceived.setText("0,00");
                    status.setText("Venda DEMO registrada · " + sale.saleId() + " · total " + formatMoney(sale.totalAmount())
                            + (sale.payment().changeAmount().isZero() ? "" : " · troco " + formatMoney(sale.payment().changeAmount())));
                    setBusy(false);
                });
            } catch (RuntimeException failure) {
                Platform.runLater(() -> { status.setText("Venda não concluída: " + failure.getMessage()); setBusy(false); });
            }
        });
    }

    private void setBusy(boolean busy) {
        searchButton.setDisable(busy); addButton.setDisable(busy); confirmButton.setDisable(busy);
        incrementButton.setDisable(busy); decrementButton.setDisable(busy); removeButton.setDisable(busy);
        paymentMethod.setDisable(busy); amountReceived.setDisable(busy || paymentMethod.getValue() != PaymentChoice.CASH);
    }

    private static BigDecimal formatPriceAmount(CatalogProduct product) {
        return product.salePrices().stream().filter(price -> "BRL".equals(price.currency())).findFirst()
                .map(price -> price.amount().setScale(2, java.math.RoundingMode.HALF_UP))
                .orElse(BigDecimal.ZERO);
    }

    private static String formatPrice(CatalogProduct product) { return formatMoney(new Money(formatPriceAmount(product), "BRL")); }
    private static String formatMoney(Money money) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR"));
        format.setCurrency(java.util.Currency.getInstance(money.currency()));
        return format.format(money.amount());
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title); alert.setHeaderText(title); alert.setContentText(message); alert.showAndWait();
    }

    private void closeDemo() {
        if (!closeRequested.compareAndSet(false, true)) return;
        if (environment == null) { worker.shutdown(); return; }
        worker.execute(() -> {
            try { environment.close(); }
            catch (IOException failure) { System.err.println("Falha ao remover banco temporário DEMO: " + failure.getMessage()); }
        });
        worker.shutdown();
    }

    @Override public void stop() { closeDemo(); }
    public static void main(String[] args) { launch(args); }

    private enum PaymentChoice {
        CASH("Dinheiro", PaymentMethod.CASH),
        CARD_DEBIT("Cartão de débito · manual", PaymentMethod.CARD_DEBIT),
        CARD_CREDIT("Cartão de crédito · manual", PaymentMethod.CARD_CREDIT),
        PIX("Pix · conferência manual", PaymentMethod.PIX);
        private final String label;
        private final PaymentMethod method;
        PaymentChoice(String label, PaymentMethod method) { this.label = label; this.method = method; }
        @Override public String toString() { return label; }
    }
}
