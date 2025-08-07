package org.example.pult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.web.WebView;
import javafx.scene.input.MouseButton;
import javafx.stage.FileChooser;
import org.example.pult.model.ArmatureCoords;
import org.example.pult.util.ArmatureExcelService;
import org.example.pult.util.ExcelService;
import org.example.pult.RowDataDynamic;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class MainController {
    @FXML private TextField searchField;
    @FXML private TableView<RowDataDynamic> tableCanvas0;
    @FXML private TableView<RowDataDynamic> armatureTable;
    @FXML private WebView pdfWebView;
    @FXML private AnchorPane rootPane;
    @FXML private Button clearSearchButton;
    @FXML private ProgressIndicator pdfLoadingIndicator;
    @FXML private TextArea pdfLogArea;
    @FXML private Button openSchemeInExternalWindowButton;
    @FXML private TabPane tabPane;
    @FXML private Tab pdfTab;

    private ExcelDataManager excelManager;
    private PDFViewerManager pdfManager;
    private String DATA_BASE_DIRECTORY;

    private static final String DATABASE_SUBFOLDER = "Databases";
    private static final String SIGNALS_EXCEL_FILE_NAME = "Oborudovanie_BSCHU.xlsx";
    private static final String ARMATURE_EXCEL_FILE_NAME = "Armatures.xlsx";

    @FXML
    public void initialize() {
        try {
            DATA_BASE_DIRECTORY = resolveDataDirectory();
            validateDataDirectory();

            String signalsExcelPath = DATA_BASE_DIRECTORY + File.separator + DATABASE_SUBFOLDER + File.separator + SIGNALS_EXCEL_FILE_NAME;
            String armaturesExcelPath = DATA_BASE_DIRECTORY + File.separator + DATABASE_SUBFOLDER + File.separator + ARMATURE_EXCEL_FILE_NAME;

            ExcelService excelService = new ExcelService(signalsExcelPath);
            ArmatureExcelService armatureExcelService = new ArmatureExcelService(armaturesExcelPath);

            pdfManager = new PDFViewerManager();
            pdfManager.setLoadingListener(new PDFViewerManager.PDFLoadingListener() {
                @Override
                public void onLoadingStart() {
                    Platform.runLater(() -> {
                        // --- Переключение на вкладку схемы при начале загрузки PDF ---
                        if (tabPane != null && pdfTab != null) {
                            tabPane.getSelectionModel().select(pdfTab);
                        }
                        // -----------------------------------------------------------
                        addToPdfLog("DEBUG: onLoadingStart() вызван.");
                        pdfLoadingIndicator.setVisible(true);
                        pdfLoadingIndicator.setManaged(true);
                        pdfLoadingIndicator.setProgress(-1);
                        addToPdfLog("Загрузка PDF...");
                    });
                }

                @Override
                public void onLoadingComplete() {
                    Platform.runLater(() -> {
                        addToPdfLog("DEBUG: onLoadingComplete() вызван.");
                        pdfLoadingIndicator.setVisible(false);
                        pdfLoadingIndicator.setManaged(false);
                        pdfLoadingIndicator.setProgress(0);
                        addToPdfLog("✓ PDF загружен и отрендерен.");
                    });
                }

                @Override
                public void onLoadingError(String error) {
                    Platform.runLater(() -> {
                        addToPdfLog("DEBUG: onLoadingError() вызван.");
                        pdfLoadingIndicator.setVisible(false);
                        pdfLoadingIndicator.setManaged(false);
                        pdfLoadingIndicator.setProgress(0);
                        addToPdfLog("Ошибка загрузки PDF: " + error);
                        showErrorAlert("Ошибка загрузки PDF: " + error);
                    });
                }
            });
            pdfManager.initialize(pdfWebView, DATA_BASE_DIRECTORY);

            excelManager = new ExcelDataManager(excelService, armatureExcelService, pdfManager, DATA_BASE_DIRECTORY);
            excelManager.initializeTables(tableCanvas0, armatureTable);
            excelManager.setupSearch(searchField, clearSearchButton);

            setupArmatureTableListener();

            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                openSchemeInExternalWindowButton.setOnAction(event -> handleOpenSchemeInExternalWindow());
            } else {
                openSchemeInExternalWindowButton.setDisable(true);
                openSchemeInExternalWindowButton.setText("Внешнее открытие не поддерживается");
            }

            addToPdfLog("Запуск системы...");
            addToPdfLog("✓ Система инициализирована");

        } catch (Exception e) {
            e.printStackTrace();
            showErrorAlert("Ошибка инициализации приложения: " + e.getMessage());
        }
    }

    private void setupArmatureTableListener() {
        armatureTable.setOnMouseClicked(event -> {
            if (event.getButton().equals(MouseButton.PRIMARY) && event.getClickCount() == 2) {
                RowDataDynamic selectedRow = armatureTable.getSelectionModel().getSelectedItem();

                if (selectedRow == null) {
                    return;
                }

                String pdfFileName = selectedRow.getProperty("Схема").get();
                String armatureId = selectedRow.getProperty("Обозначение").get();

                if (pdfFileName == null || pdfFileName.trim().isEmpty() || armatureId == null) {
                    addToPdfLog("В выбранной строке не найдена информация для навигации.");
                    return;
                }

                try {
                    String jsonFilePath = Paths.get(DATA_BASE_DIRECTORY, PDFViewerManager.SCHEMES_SUBFOLDER, "armature_coords.json").toString();
                    Path pdfPath = Paths.get(DATA_BASE_DIRECTORY, PDFViewerManager.SCHEMES_SUBFOLDER, pdfFileName);

                    if (!Files.exists(pdfPath)) {
                        showErrorAlert("Файл схемы не найден: " + pdfPath);
                        return;
                    }

                    Map<String, Map<String, ArmatureCoords>> allCoords = pdfManager.readAllArmatureCoordinatesFromFile(jsonFilePath);

                    ArmatureCoords coords = null;
                    if (allCoords != null && allCoords.containsKey(pdfFileName)) {
                        coords = allCoords.get(pdfFileName).get(armatureId);
                    }

                    // --- Переключаем вкладку и загружаем PDF только если все проверки прошли успешно ---
                    if (tabPane != null && pdfTab != null) {
                        tabPane.getSelectionModel().select(pdfTab);
                        addToPdfLog("DEBUG: Все проверки пройдены, переключаемся на вкладку схемы.");
                    } else {
                        addToPdfLog("ERROR: tabPane или pdfTab не инициализированы. Невозможно переключить вкладку.");
                    }
                    // ---------------------------------------------------------------------------------

                    if (coords != null) {
                        addToPdfLog("Навигация к арматуре: " + armatureId + " на схеме " + pdfFileName + ". Координаты: x=" + coords.getX() + ", y=" + coords.getY());
                        pdfManager.loadPdfCentered(pdfPath.toString(), coords);
                    } else {
                        addToPdfLog("Координаты для '" + armatureId + "' в файле '" + pdfFileName + "' не найдены. Открываю схему без навигации.");
                        pdfManager.loadPdfCentered(pdfPath.toString(), null);
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                    showErrorAlert("Ошибка чтения файла координат: " + e.getMessage());
                } catch (Exception e) {
                    e.printStackTrace();
                    showErrorAlert("Произошла непредвиденная ошибка: " + e.getMessage());
                }
            }
        });
    }

    @FXML
    private void handleOpenPdfScheme() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Открыть файл схемы PDF");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

        File initialDir = new File(DATA_BASE_DIRECTORY + File.separator + PDFViewerManager.SCHEMES_SUBFOLDER);
        if (initialDir.exists() && initialDir.isDirectory()) {
            fileChooser.setInitialDirectory(initialDir);
        }

        File file = fileChooser.showOpenDialog(rootPane.getScene().getWindow());

        if (file != null) {
            try {
                validateDataDirectory();
                Path targetPath = Paths.get(DATA_BASE_DIRECTORY)
                        .resolve(PDFViewerManager.SCHEMES_SUBFOLDER)
                        .resolve(file.getName());

                Files.copy(file.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                addToPdfLog("Схема успешно скопирована: " + targetPath.getFileName());

                if (tabPane != null && pdfTab != null) {
                    tabPane.getSelectionModel().select(pdfTab);
                }

                pdfManager.loadPdf(targetPath.toString(), null);
            } catch (IOException e) {
                showErrorAlert("Ошибка при открытии файла: " + e.getMessage());
            }
        }
    }

    @FXML
    private void handleOpenSchemeInExternalWindow() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Выбрать схему PDF для внешнего открытия");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

        File initialDir = new File(DATA_BASE_DIRECTORY + File.separator + PDFViewerManager.SCHEMES_SUBFOLDER);
        if (initialDir.exists() && initialDir.isDirectory()) {
            fileChooser.setInitialDirectory(initialDir);
        } else {
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
            addToPdfLog("Предупреждение: директория схем не найдена: " + initialDir.getAbsolutePath() + ". Открытие FileChooser в домашней директории.");
        }

        File selectedFile = fileChooser.showOpenDialog(rootPane.getScene().getWindow());

        if (selectedFile != null) {
            try {
                addToPdfLog("Попытка открыть файл внешним приложением: " + selectedFile.getAbsolutePath());
                Desktop.getDesktop().open(selectedFile);
                addToPdfLog("✓ Файл успешно открыт внешним приложением.");
            } catch (IOException e) {
                addToPdfLog("Ошибка при открытии файла внешним приложением: " + e.getMessage());
                showErrorAlert("Ошибка при открытии файла: " + e.getMessage());
            } catch (UnsupportedOperationException e) {
                addToPdfLog("Ошибка: Открытие файлов внешним приложением не поддерживается вашей системой.");
                showErrorAlert("Ошибка: Открытие файлов внешним приложением не поддерживается.");
            }
        } else {
            addToPdfLog("Выбор файла отменен.");
        }
    }

    private void addToPdfLog(String message) {
        Platform.runLater(() -> {
            pdfLogArea.appendText(message + "\n");
            pdfLogArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private String resolveDataDirectory() {
        Path appRootPath = Paths.get(System.getProperty("user.dir"));
        return appRootPath.resolve("data").toAbsolutePath().toString();
    }

    private void validateDataDirectory() throws IOException {
        File dataDir = new File(DATA_BASE_DIRECTORY);
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            throw new IOException("Не найдена папка данных: " + DATA_BASE_DIRECTORY);
        }
    }

    @FXML
    private void handleUndo() {
        excelManager.handleUndo();
        pdfLogArea.appendText("Выполнена отмена действия\n");
    }

    @FXML
    private void handleRedo() {
        excelManager.handleRedo();
        pdfLogArea.appendText("Повторено действие\n");
    }

    @FXML
    private void handleZoomIn() {
        // ИСПРАВЛЕНО: Исправлен ID на zoom_in, как в viewer.html
        pdfWebView.getEngine().executeScript("document.getElementById('zoom_in').click();");
    }

    @FXML
    private void handleZoomOut() {
        // ИСПРАВЛЕНО: Исправлен ID на zoom_out, как в viewer.html
        pdfWebView.getEngine().executeScript("document.getElementById('zoom_out').click();");
    }

    @FXML
    private void handleRotateLeft() {
        // В FXML нет кнопок поворота. Если добавите, используйте этот код.
    }

    @FXML
    private void handleRotateRight() {
        // В FXML нет кнопок поворота. Если добавите, используйте этот код.
    }

    @FXML
    private void handleToggleSidebar() {
        // В FXML нет кнопок. Если добавите, используйте этот код.
    }

    @FXML
    private void clearSearch() {
        searchField.clear();
    }

    private void showErrorAlert(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Ошибка");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}