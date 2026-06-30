package com.ossfilebrowse.plus;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.image.Image;
import java.io.InputStream;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            FXMLLoader loader = new FXMLLoader();
            loader.setLocation(getClass().getResource("/fxml/main.fxml"));
            Parent content = loader.load();
            Scene scene = new Scene(content);
            // 添加图标
            InputStream iconStream = getClass().getResourceAsStream("/images/logo.png");
            Image icon = new Image(iconStream);
            primaryStage.getIcons().add(icon);
            primaryStage.setTitle("OSSFileBrowse-plus（仅限授权安全测试，未授权测试后果自负）");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(1200);
            primaryStage.setMinHeight(760);
            primaryStage.setResizable(true);
            primaryStage.show();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args){
        launch(args);
    }
}
