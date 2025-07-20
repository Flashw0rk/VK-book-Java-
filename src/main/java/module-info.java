module org.example.pult {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web; // Если используете WebView
    requires jdk.jsobject; // <-- Эту строку необходимо добавить
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.annotation;
    requires org.apache.poi.poi;
    requires org.apache.poi.ooxml;

    // Эту строку необходимо добавить для использования java.awt.Desktop
    requires java.desktop;

    // Открываем пакеты для FXML
    opens org.example.pult to javafx.fxml;
    exports org.example.pult;
    exports org.example.pult.model; // Убедитесь, что ваш пакет model также экспортируется, если он используется FXML
    exports org.example.pult.util; // Экспортируем пакет util
}