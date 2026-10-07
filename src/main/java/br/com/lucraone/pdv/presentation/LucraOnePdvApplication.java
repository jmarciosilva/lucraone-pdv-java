package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.ApplicationMetadata;
import br.com.lucraone.pdv.application.terminal.InvalidBootstrapConfigurationException;
import br.com.lucraone.pdv.application.terminal.TerminalDiagnostics;
import br.com.lucraone.pdv.application.terminal.TerminalService;
import br.com.lucraone.pdv.domain.terminal.ProvisioningStatus;
import br.com.lucraone.pdv.infrastructure.configuration.PropertiesBootstrapConfigurationSource;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcTerminalRepository;
import br.com.lucraone.pdv.infrastructure.persistence.JdbcTransactionManager;
import br.com.lucraone.pdv.infrastructure.persistence.LocalDataDirectory;
import br.com.lucraone.pdv.infrastructure.persistence.LocalDatabaseException;
import br.com.lucraone.pdv.infrastructure.persistence.LocalDatabaseInitializer;
import br.com.lucraone.pdv.infrastructure.persistence.SqliteConnectionFactory;
import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Ponto de entrada da aplicação desktop JavaFX.
 */
public class LucraOnePdvApplication extends Application {

    @Override
    public void start(Stage stage) {
        TerminalDiagnostics diagnostics;
        try {
            diagnostics = createTerminalService().initialize();
        } catch (LocalDatabaseException exception) {
            show(stage, "Não foi possível preparar o banco local do PDV. " + exception.getMessage());
            return;
        } catch (InvalidBootstrapConfigurationException exception) {
            show(stage, "A configuração bootstrap do PDV é inválida. " + exception.getMessage());
            return;
        }

        show(stage, diagnostics.provisioningStatus() == ProvisioningStatus.PROVISIONED
                ? "Terminal provisionado."
                : "Terminal não provisionado.");
    }

    /**
     * Composition root: wires the local adapters; the presentation itself never runs SQL.
     */
    private TerminalService createTerminalService() {
        LocalDataDirectory directory = new LocalDataDirectory();
        SqliteConnectionFactory connections = new LocalDatabaseInitializer(directory).initialize();
        return new TerminalService(
                new JdbcTerminalRepository(new JdbcTransactionManager(connections)),
                new PropertiesBootstrapConfigurationSource(directory.bootstrapConfigurationFile()),
                directory.databasePath().toString()
        );
    }

    private void show(Stage stage, String text) {
        Label title = new Label(ApplicationMetadata.name());
        title.getStyleClass().add("application-title");

        Label message = new Label(text);
        message.setWrapText(true);
        message.getStyleClass().add("application-message");

        VBox root = new VBox(12, title, message);
        root.getStyleClass().add("application-root");
        root.setAlignment(Pos.CENTER);

        Scene scene = new Scene(root, 1000, 700);
        scene.getStylesheets().add(
                getClass().getResource("/br/com/lucraone/pdv/presentation/application.css").toExternalForm()
        );

        stage.setTitle(ApplicationMetadata.name());
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
