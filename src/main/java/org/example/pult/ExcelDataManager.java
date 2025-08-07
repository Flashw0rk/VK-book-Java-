package org.example.pult;

// --- Начало изменений ---
import javafx.geometry.Pos;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
// --- Конец изменений ---
import java.nio.file.Paths;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.example.pult.util.ArmatureExcelService;
import org.example.pult.util.ExcelService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;
import java.util.function.Predicate;

public class ExcelDataManager {
    private interface UndoableOperation {
        void undo();
        void redo();
        String getDescription();
    }

    private final Stack<UndoableOperation> undoStack = new Stack<>();
    private final Stack<UndoableOperation> redoStack = new Stack<>();
    private boolean performingUndoRedo = false;

    private final ExcelService excelService;
    private final ArmatureExcelService armatureExcelService;
    private final StringProperty searchText = new SimpleStringProperty("");
    private final PDFViewerManager pdfViewerManager;
    private final String dataDirectory;

    private TableView<RowDataDynamic> tableCanvas0;
    private TableView<RowDataDynamic> armatureTable;

    private static final double TEXT_PADDING = 8;
    private static final double LINE_SPACING = 2;
    private static final double HEIGHT_BUFFER = 5;
    private static final double MIN_ROW_HEIGHT = 28;
    private static final double DEFAULT_COLUMN_WIDTH = 500;
    private static final Font DEFAULT_CELL_FONT = new Font("System", 10);
    private static final String HIGHLIGHT_STYLE =
            "-fx-background-color: rgba(255,165,0,0.3);" +
                    "-fx-border-color: #FFA500;" +
                    "-fx-border-width: 1px;" +
                    "-fx-border-radius: 2px;";

    public ExcelDataManager(ExcelService excelService, ArmatureExcelService armatureExcelService,
                            PDFViewerManager pdfViewerManager, String dataDirectory) {
        this.excelService = excelService;
        this.armatureExcelService = armatureExcelService;
        this.pdfViewerManager = pdfViewerManager;
        this.dataDirectory = dataDirectory;
    }

    public void initializeTables(TableView<RowDataDynamic> table1,
                                 TableView<RowDataDynamic> table2) throws IOException {
        this.tableCanvas0 = table1;
        this.armatureTable = table2;

        setupTableView(tableCanvas0);
        setupTableView(armatureTable);

        loadTableData(tableCanvas0, excelService, "Сигналы БЩУ", false);
        loadTableData(armatureTable, armatureExcelService, "Арматура", true);
    }

    private void setupTableView(TableView<RowDataDynamic> tableView) {
        tableView.setEditable(true);
        tableView.getSelectionModel().setCellSelectionEnabled(true);
        tableView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        tableView.setFixedCellSize(Region.USE_COMPUTED_SIZE);
        tableView.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(RowDataDynamic item, boolean empty) {
                super.updateItem(item, empty);
                if (!empty && item != null) {
                    Platform.runLater(() -> {
                        requestLayout();
                        if (getTableView() != null) {
                            getTableView().requestLayout();
                        }
                    });
                }
            }
        });
        tableView.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal.doubleValue() > 0) {
                Platform.runLater(tableView::refresh);
            }
        });
    }

    private TableColumn<RowDataDynamic, String> createMultiLineHeaderColumn(String header, int width) {
        TableColumn<RowDataDynamic, String> column = new TableColumn<>();

        Label label = new Label(header);
        label.setStyle("-fx-alignment: CENTER; -fx-font-weight: BOLD; -fx-text-fill: #2E2E2E;");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(40);

        column.setGraphic(label);
        column.setUserData(header);
        column.setPrefWidth(width);
        return column;
    }

    private void loadTableData(TableView<RowDataDynamic> tableView, ExcelService service,
                               String sheetName, boolean isArmatureTable) throws IOException {
        List<String> headers = service.readHeaders(sheetName);
        ObservableList<RowDataDynamic> data = FXCollections.observableArrayList(service.readAllRows(sheetName));

        Map<String, Integer> columnWidths = service.getColumnWidths(sheetName);

        tableView.getColumns().clear();
        for (String header : headers) {
            boolean isPdfSchemeColumn = isArmatureTable && "PDF_Схема_и_ID_арматуры".equalsIgnoreCase(header);
            if (isPdfSchemeColumn) {
                continue;
            }

            TableColumn<RowDataDynamic, String> column;
            int width = columnWidths.getOrDefault(header, (int)DEFAULT_COLUMN_WIDTH);

            column = createMultiLineHeaderColumn(header, width);
            column.setCellValueFactory(cellData -> cellData.getValue().getProperty(header));

            if (isArmatureTable && "Арматура".equalsIgnoreCase(header)) {
                column.setCellFactory(tc -> new ArmatureLinkTableCell(searchText, pdfViewerManager, dataDirectory)); // Теперь принимает 3 параметра
            } else {
                column.setCellFactory(tc -> new CustomEditableTableCell(searchText));
            }
            tableView.getColumns().add(column);
        }

        FilteredList<RowDataDynamic> filteredData = new FilteredList<>(data, p -> true);
        tableView.setItems(filteredData);
        Platform.runLater(tableView::refresh);
    }

    private void recordChange(UndoableOperation operation) {
        if (!performingUndoRedo) {
            undoStack.push(operation);
            redoStack.clear();
            System.out.println("Записано новое действие: " + operation.getDescription());
        }
    }

    class CustomEditableTableCell extends TableCell<RowDataDynamic, String> {
        private final StringProperty searchText;
        private final Text textGraphic = new Text();
        private final TextArea editorTextArea = new TextArea();
        private String originalValue;

        public CustomEditableTableCell(StringProperty searchText) {
            this.searchText = searchText;
            setupGraphic();
        }

        private void setupGraphic() {
            textGraphic.setWrappingWidth(0);
            textGraphic.setLineSpacing(LINE_SPACING);
            textGraphic.setFont(DEFAULT_CELL_FONT);
            setGraphic(textGraphic);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setPrefHeight(MIN_ROW_HEIGHT);

            widthProperty().addListener((obs, oldVal, newVal) -> {
                adjustCellHeight(textGraphic, getItem(), newVal.doubleValue(), this);
            });

            editorTextArea.setWrapText(true);
            editorTextArea.setFont(DEFAULT_CELL_FONT);
            editorTextArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    if (event.isShiftDown()) {
                        editorTextArea.appendText("\n");
                    } else {
                        commitEdit(editorTextArea.getText());
                        event.consume();
                    }
                } else if (event.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    event.consume();
                }
            });
            editorTextArea.focusedProperty().addListener((obs, oldVal, newVal) -> {
                if (!newVal && editorTextArea.isVisible()) {
                    commitEdit(editorTextArea.getText());
                }
            });
            editorTextArea.prefHeightProperty().bind(textGraphic.layoutBoundsProperty().map(bounds -> bounds.getHeight() + HEIGHT_BUFFER));
        }

        private void adjustCellHeight(Node graphicNode, String content, double currentWidth, TableCell<?, ?> targetCell) {
            if (content == null || content.isEmpty()) {
                targetCell.setPrefHeight(MIN_ROW_HEIGHT);
                return;
            }

            double actualWidth = currentWidth - TEXT_PADDING * 2;
            if (actualWidth <= 0) {
                actualWidth = DEFAULT_COLUMN_WIDTH - TEXT_PADDING * 2;
            }

            if (graphicNode instanceof Text) {
                Text text = (Text) graphicNode;
                text.setWrappingWidth(actualWidth);
                double textHeight = text.getLayoutBounds().getHeight();
                double newHeight = Math.max(MIN_ROW_HEIGHT, textHeight + HEIGHT_BUFFER);
                targetCell.setPrefHeight(newHeight);
            }
        }

        private void updateHighlight() {
            String item = getItem();
            String search = searchText.get();
            if (item != null && search != null && !search.isEmpty() && item.toLowerCase().contains(search)) {
                setStyle(HIGHLIGHT_STYLE);
            } else {
                setStyle("");
            }
        }

        @Override
        public void startEdit() {
            if (!isEditable() || !getTableView().isEditable() || !getTableColumn().isEditable()) {
                return;
            }
            super.startEdit();
            originalValue = getItem();
            if (editorTextArea != null) {
                editorTextArea.setText(getItem());
                setGraphic(editorTextArea);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                Platform.runLater(() -> editorTextArea.requestFocus());
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            setGraphic(textGraphic);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            textGraphic.setText(getItem());
            updateHighlight();
            adjustCellHeight(textGraphic, getItem(), getWidth(), this);
        }

        @Override
        public void commitEdit(String newValue) {
            if (!isEditing()) return;

            super.commitEdit(newValue);

            RowDataDynamic row = getTableRow().getItem();
            String columnName = (String) getTableColumn().getUserData();

            if (!Objects.equals(originalValue, newValue)) {
                int sourceIndex = getTableView().getItems().indexOf(row);
                recordChange(new CellEditOperation(getTableView(), sourceIndex, columnName, originalValue, newValue));
            }

            if (row != null && columnName != null) {
                row.getProperty(columnName).set(newValue);
            }

            setGraphic(textGraphic);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            textGraphic.setText(newValue);
            updateHighlight();
            adjustCellHeight(textGraphic, newValue, getWidth(), this);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);

            setGraphic(null);
            setStyle("");
            setCursor(javafx.scene.Cursor.DEFAULT);

            if (empty || item == null) {
                return;
            }

            textGraphic.setText(item);
            setGraphic(textGraphic);
            updateHighlight();
            adjustCellHeight(textGraphic, item, getWidth(), this);
        }
    }

    // --- Начало изменений ---
    class ArmatureLinkTableCell extends TableCell<RowDataDynamic, String> {
        private final StringProperty searchText;
        private final PDFViewerManager pdfViewerManager;
        private String dataDirectory;
        private final Text textGraphic = new Text();

        // Загружаем иконку один раз, чтобы не делать это для каждой ячейки
        private static final Image PDF_ICON = new Image(
                Objects.requireNonNull(ExcelDataManager.class.getResourceAsStream("/icons/pdf_icon.png"))
        );

        public ArmatureLinkTableCell(StringProperty searchText, PDFViewerManager pdfViewerManager, String dataDirectory) {
            this.searchText = searchText;
            this.pdfViewerManager = pdfViewerManager;
            this.dataDirectory = dataDirectory;
            setupGraphic();
        }

        private void setupGraphic() {
            textGraphic.setWrappingWidth(0);
            textGraphic.setLineSpacing(LINE_SPACING);
            textGraphic.setFont(DEFAULT_CELL_FONT);

            setPrefHeight(MIN_ROW_HEIGHT);

            widthProperty().addListener((obs, oldVal, newVal) -> {
                adjustCellHeight(this, getItem(), newVal.doubleValue());
            });

            setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && event.getButton().equals(MouseButton.PRIMARY) && !isEmpty() && pdfViewerManager != null) {
                    RowDataDynamic row = getTableRow().getItem();
                    if (row != null) {
                        String pdfFileName = row.getProperty("PDF_Схема_и_ID_арматуры").get();
                        String armatureId = row.getProperty("Арматура").get();
                        if (pdfFileName != null && !pdfFileName.trim().isEmpty()) {
                            String fullPdfPath = Paths.get(dataDirectory, "Schemes", pdfFileName).toString();
                            pdfViewerManager.loadPdf(fullPdfPath, armatureId);
                        }
                    }
                }
            });
        }

        private void adjustCellHeight(TableCell<?,?> targetCell, String content, double currentWidth) {
            if (content == null || content.isEmpty()) {
                targetCell.setPrefHeight(MIN_ROW_HEIGHT);
                return;
            }
            // Используем сам HBox для расчета высоты
            if (targetCell.getGraphic() instanceof HBox) {
                HBox hbox = (HBox) targetCell.getGraphic();
                // Устанавливаем ширину текста внутри HBox для корректного расчета переноса строк
                textGraphic.setWrappingWidth(currentWidth - TEXT_PADDING * 2 - 20); // 20 - примерный размер иконки и отступа
                double textHeight = textGraphic.getLayoutBounds().getHeight();
                double newHeight = Math.max(MIN_ROW_HEIGHT, textHeight + HEIGHT_BUFFER);
                targetCell.setPrefHeight(newHeight);
            }
        }

        private void updateHighlight() {
            String item = getItem();
            String search = searchText.get();
            // Сначала сбрасываем стиль, чтобы избежать дублирования
            getStyleClass().remove("highlighted");
            if (item != null && search != null && !search.isEmpty() && item.toLowerCase().contains(search)) {
                // Используем CSS класс для подсветки вместо инлайн-стиля
                getStyleClass().add("highlighted");
            }
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);

            // Сбрасываем все перед новой отрисовкой
            setGraphic(null);
            setStyle(""); // Сбрасываем инлайн стили
            setCursor(javafx.scene.Cursor.DEFAULT);

            if (empty || item == null) {
                return;
            }

            textGraphic.setText(item);
            HBox contentBox = new HBox(5); // HBox с отступом 5px
            contentBox.setAlignment(Pos.CENTER_LEFT);
            contentBox.getChildren().add(textGraphic);

            RowDataDynamic row = getTableRow() != null ? getTableRow().getItem() : null;
            if (row != null) {
                String pdfLink = row.getProperty("PDF_Схема_и_ID_арматуры").get();

                if (pdfLink != null && !pdfLink.trim().isEmpty()) {
                    // Делаем текст синим и подчеркнутым
                    textGraphic.setStyle("-fx-fill: blue; -fx-underline: true;");
                    setCursor(javafx.scene.Cursor.HAND);

                    // Создаем ImageView для иконки
                    ImageView iconView = new ImageView(PDF_ICON);
                    iconView.setFitHeight(16);
                    iconView.setFitWidth(16);
                    iconView.setPreserveRatio(true);

                    // Добавляем иконку в HBox
                    contentBox.getChildren().add(iconView);
                } else {
                    // Убираем стили, если ссылки нет
                    textGraphic.setStyle("");
                }
            }

            setGraphic(contentBox);
            updateHighlight(); // Подсветка поиска
            adjustCellHeight(this, item, getWidth());
        }
    }
    // --- Конец изменений ---

    private class CellEditOperation implements UndoableOperation {
        private final TableView<RowDataDynamic> tableView;
        private final int rowIndex;
        private final String columnName;
        private final String oldValue;
        private final String newValue;

        public CellEditOperation(TableView<RowDataDynamic> tableView, int rowIndex, String columnName, String oldValue, String newValue) {
            this.tableView = tableView;
            this.rowIndex = rowIndex;
            this.columnName = columnName;
            this.oldValue = oldValue;
            this.newValue = newValue;
        }

        @Override
        public void undo() {
            Platform.runLater(() -> {
                ObservableList<RowDataDynamic> items = tableView.getItems();
                if (rowIndex >= 0 && rowIndex < items.size()) {
                    RowDataDynamic row = items.get(rowIndex);
                    row.getProperty(columnName).set(oldValue);
                    tableView.refresh();
                }
            });
        }

        @Override
        public void redo() {
            Platform.runLater(() -> {
                ObservableList<RowDataDynamic> items = tableView.getItems();
                if (rowIndex >= 0 && rowIndex < items.size()) {
                    RowDataDynamic row = items.get(rowIndex);
                    row.getProperty(columnName).set(newValue);
                    tableView.refresh();
                }
            });
        }

        @Override
        public String getDescription() {
            return "Изменение ячейки [" + rowIndex + ", " + columnName + "] с '" + oldValue + "' на '" + newValue + "'";
        }
    }

    public void handleUndo() {
        if (!undoStack.isEmpty() && !performingUndoRedo) {
            performingUndoRedo = true;
            UndoableOperation operation = undoStack.pop();
            operation.undo();
            redoStack.push(operation);
            performingUndoRedo = false;
        }
    }

    public void handleRedo() {
        if (!redoStack.isEmpty() && !performingUndoRedo) {
            performingUndoRedo = true;
            UndoableOperation operation = redoStack.pop();
            operation.redo();
            undoStack.push(operation);
            performingUndoRedo = false;
        }
    }

    public void setupSearch(TextField searchField, Button clearButton) {
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            searchText.set(newVal.toLowerCase());
            filterTables(newVal);
            Platform.runLater(() -> {
                tableCanvas0.refresh();
                armatureTable.refresh();
            });
            clearButton.setVisible(!newVal.isEmpty());
        });
        clearButton.setOnAction(e -> {
            searchField.clear();
            searchText.set("");
        });
        clearButton.setVisible(false);
    }

    private void filterTables(String searchText) {
        Predicate<RowDataDynamic> predicate = createPredicate(searchText);
        if (tableCanvas0.getItems() instanceof FilteredList) {
            ((FilteredList<RowDataDynamic>) tableCanvas0.getItems()).setPredicate(predicate);
        }
        if (armatureTable.getItems() instanceof FilteredList) {
            ((FilteredList<RowDataDynamic>) armatureTable.getItems()).setPredicate(predicate);
        }
    }

    private Predicate<RowDataDynamic> createPredicate(String searchText) {
        return rowData -> {
            if (searchText == null || searchText.isEmpty()) {
                return true;
            }
            String lowerCaseSearchText = searchText.toLowerCase();
            for (StringProperty prop : rowData.getAllProperties()) {
                if (prop.get() != null && prop.get().toLowerCase().contains(lowerCaseSearchText)) {
                    return true;
                }
            }
            return false;
        };
    }
}