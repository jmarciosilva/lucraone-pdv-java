package br.com.lucraone.pdv.presentation;

import br.com.lucraone.pdv.application.ApplicationMetadata;
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
        Label title = new Label(ApplicationMetadata.name());
        title.getStyleClass().add("application-title");

        Label message = new Label("Ambiente inicial configurado com sucesso.");
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
