package org.example.pult;

// 1Версия, где работает иконка в арматуре и открывается вкладка с масштабированием, выделением и центрированием

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.web.WebView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.FileChooser;
import netscape.javascript.JSObject;
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
import javafx.scene.paint.Color;

public class MainController {
    @FXML private TextField searchField;
    @FXML private TableView<RowDataDynamic> tableCanvas0;
    @FXML private TableView<RowDataDynamic> armatureTable;
    @FXML private WebView pdfWebView;
    @FXML private WebView editorWebView;
    @FXML private AnchorPane rootPane;
    @FXML private Button clearSearchButton;
    @FXML private ProgressIndicator pdfLoadingIndicator;
    @FXML private ProgressIndicator editorLoadingIndicator;
    @FXML private TextArea pdfLogArea;
    @FXML private TextArea editorLogArea;
    @FXML private Button openSchemeInExternalWindowButton;
    @FXML private Button openSchemeInExternalWindowButtonEditor;
    @FXML private ToggleButton editorShowAllButton;
    @FXML private Button editorSaveButton;
    @FXML private Button editorUndoButton;
    @FXML private Button editorRedoButton;
    @FXML private ToggleButton editorMarkToggle;
    @FXML private TabPane tabPane;
    @FXML private Tab pdfTab;
    @FXML private Tab editorTab;

    private ExcelDataManager excelManager;
    private PDFViewerManager pdfManager;
    private PDFViewerManager editorPdfManager;
    private String DATA_BASE_DIRECTORY;
    private String editorCurrentPdfFileName;
    private static final String EDITOR_PREVIEW_MARKER_ID = "__editor_preview__";
    private volatile boolean editorDialogOpen = false;
    private ContextMenu editorActiveContextMenu;
    private final java.util.Set<String> editorDeletedMarkerIds = new java.util.HashSet<>();
    private final java.util.Deque<String> editorUndoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<String> editorRedoStack = new java.util.ArrayDeque<>();

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

            // --- Инициализация PDF Viewer для вкладки Редактор (отдельно, не затрагивая существующий) ---
            editorPdfManager = new PDFViewerManager();
            editorPdfManager.setLoadingListener(new PDFViewerManager.PDFLoadingListener() {
                @Override
                public void onLoadingStart() {
                    Platform.runLater(() -> {
                        if (tabPane != null && editorTab != null) {
                            tabPane.getSelectionModel().select(editorTab);
                        }
                        addToEditorLog("DEBUG: [Editor] onLoadingStart() вызван.");
                        if (editorLoadingIndicator != null) {
                            editorLoadingIndicator.setVisible(true);
                            editorLoadingIndicator.setManaged(true);
                            editorLoadingIndicator.setProgress(-1);
                        }
                        addToEditorLog("Загрузка PDF в редакторе...");
                    });
                }

                @Override
                public void onLoadingComplete() {
                    Platform.runLater(() -> {
                        addToEditorLog("DEBUG: [Editor] onLoadingComplete() вызван.");
                        if (editorLoadingIndicator != null) {
                            editorLoadingIndicator.setVisible(false);
                            editorLoadingIndicator.setManaged(false);
                            editorLoadingIndicator.setProgress(0);
                        }
                        addToEditorLog("✓ PDF загружен и отрендерен в редакторе.");
                        // Больше не отображаем метки автоматически — сначала пустая схема
                    });
                }

                @Override
                public void onLoadingError(String error) {
                    Platform.runLater(() -> {
                        addToEditorLog("DEBUG: [Editor] onLoadingError() вызван.");
                        if (editorLoadingIndicator != null) {
                            editorLoadingIndicator.setVisible(false);
                            editorLoadingIndicator.setManaged(false);
                            editorLoadingIndicator.setProgress(0);
                        }
                        addToEditorLog("Ошибка загрузки PDF в редакторе: " + error);
                        showErrorAlert("Ошибка загрузки PDF (Редактор): " + error);
                    });
                }
            });
            editorPdfManager.initialize(editorWebView, DATA_BASE_DIRECTORY);

            // --- Подключаем JS-мост и обработчик кликов только для вкладки Редактор ---
            setupEditorViewerHooks();

            // Доп. логирование событий мыши поверх editorWebView (JavaFX уровень)
            if (editorWebView != null) {
                editorWebView.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
                    addToEditorLog(String.format(
                            "FX mousedown: button=%s @%.0f,%.0f", e.getButton(), e.getScreenX(), e.getScreenY()));
                    // Любой левый клик скрывает активное контекстное меню редактора
                    if (e.getButton() == MouseButton.PRIMARY) {
                        if (editorActiveContextMenu != null && editorActiveContextMenu.isShowing()) {
                            editorActiveContextMenu.hide();
                            editorActiveContextMenu = null;
                        }
                    }
                });
                editorWebView.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> addToEditorLog(String.format(
                        "FX mouseup: button=%s @%.0f,%.0f", e.getButton(), e.getScreenX(), e.getScreenY())));
                editorWebView.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> addToEditorLog(String.format(
                        "FX click: button=%s count=%d @%.0f,%.0f", e.getButton(), e.getClickCount(), e.getScreenX(), e.getScreenY())));
                // Контекстное меню по правому клику: Отметить точку здесь / Обновить страницу
                editorWebView.setOnContextMenuRequested(ev -> {
                    try {
                        ev.consume();
                        if (editorActiveContextMenu != null && editorActiveContextMenu.isShowing()) {
                            editorActiveContextMenu.hide();
                            editorActiveContextMenu = null;
                        }
                        ContextMenu menu = new ContextMenu();
                        // Узнаём, есть ли под курсором метка
                        String jsFindMarker = String.join("\n",
                                "(function(){",
                                "  try {",
                                "    var x = (typeof window.__lastClientX==='number')?window.__lastClientX:0;",
                                "    var y = (typeof window.__lastClientY==='number')?window.__lastClientY:0;",
                                "    var el = document.elementFromPoint(x,y);",
                                "    if (!el) return null;",
                                "    var node = el.closest ? el.closest('[data-mid]') : null;",
                                "    return node ? node.getAttribute('data-mid') : null;",
                                "  } catch(e){ return null; }",
                                "})();");
                        Object markerIdObj = editorWebView.getEngine().executeScript(jsFindMarker);
                        String markerId = (markerIdObj instanceof String) ? (String) markerIdObj : null;

                        if (markerId != null && editorMarkToggle != null && editorMarkToggle.isSelected()) {
                            // Меню для метки: удалить/редактировать
                            MenuItem del = new MenuItem("Удалить точку");
                            del.setOnAction(a -> deleteEditorMarker(markerId));
                            MenuItem edit = new MenuItem("Редактировать точку");
                            edit.setOnAction(a -> editEditorMarker(markerId));
                            menu.getItems().addAll(edit, del);
                            menu.show(editorWebView, ev.getScreenX(), ev.getScreenY());
                            editorActiveContextMenu = menu;
                            return;
                        }

                        // Обычное меню: добавить точку / обновить страницу
                        MenuItem addHere = new MenuItem("Отметить точку здесь…");
                        MenuItem reload = new MenuItem("Обновить страницу");
                        addHere.setOnAction(a -> {
                            try {
                                Object res = editorWebView.getEngine().executeScript(String.join("\n",
                                        "(function(){",
                                        "  try {",
                                        "    var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                                        "    if (!root) return null;",
                                        "    var canvas = root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas');",
                                        "    if (!canvas) return null;",
                                        "    var rect = canvas.getBoundingClientRect();",
                                        "    var s = (typeof window.scale === 'number') ? window.scale : 1.0;",
                                        "    var cx = (typeof window.__lastClientX === 'number') ? window.__lastClientX : (rect.left + rect.width/2);",
                                        "    var cy = (typeof window.__lastClientY === 'number') ? window.__lastClientY : (rect.top + rect.height/2);",
                                        "    var xPdf = (cx - rect.left) / s;",
                                        "    var yPdf = (cy - rect.top) / s;",
                                        "    var page = (typeof window.currentPage === 'number') ? window.currentPage : 1;",
                                        "    return {x:xPdf, y:yPdf, p:page, s:s};",
                                        "  } catch(e){ return null; }",
                                        "})();"
                                ));
                                if (res instanceof netscape.javascript.JSObject) {
                                    netscape.javascript.JSObject obj = (netscape.javascript.JSObject) res;
                                    Object ox = obj.getMember("x");
                                    Object oy = obj.getMember("y");
                                    Object op = obj.getMember("p");
                                    Object os = obj.getMember("s");
                                    if (ox instanceof Number && oy instanceof Number && op instanceof Number && os instanceof Number) {
                                        double x = ((Number) ox).doubleValue();
                                        double y = ((Number) oy).doubleValue();
                                        int page = ((Number) op).intValue();
                                        double scale = ((Number) os).doubleValue();
                                        showEditorAnnotationDialogAndSave(x, y, page, scale);
                                        return;
                                    }
                                }
                                addToEditorLog("Не удалось вычислить координаты для контекстного меню");
                            } catch (Exception ex) {
                                addToEditorLog("Ошибка вычисления координат (контекстное меню): " + ex.getMessage());
                            }
                        });
                        reload.setOnAction(a -> { try { editorWebView.getEngine().reload(); } catch (Exception ignored) {} });
                        menu.getItems().addAll(addHere, reload);
                        menu.show(editorWebView, ev.getScreenX(), ev.getScreenY());
                        editorActiveContextMenu = menu;
                    } catch (Exception ex) {
                        addToEditorLog("Ошибка показа контекстного меню (FX): " + ex.getMessage());
                    }
                });
                // Переименуем кнопку режима
                if (editorMarkToggle != null) {
                    editorMarkToggle.setText("Редактирование точек");
                }
                if (editorShowAllButton != null) {
                    editorShowAllButton.setOnAction(e -> {
                        try {
                            boolean show = editorShowAllButton.isSelected();
                            if (show) {
                                renderEditorMarkersForCurrentPdf();
                                addToEditorLog("✓ Все точки загружены из JSON для " + editorCurrentPdfFileName);
                            } else {
                                editorWebView.getEngine().executeScript("if (window.clearEditorMarkers) window.clearEditorMarkers();");
                                addToEditorLog("✓ Точки скрыты");
                            }
                        } catch (Exception ex) {
                            addToEditorLog("Ошибка переключения отображения точек: " + ex.getMessage());
                        }
                    });
                }
                if (editorSaveButton != null) {
                    editorSaveButton.setOnAction(e -> handleEditorSaveChanges());
                }
                if (editorUndoButton != null) editorUndoButton.setOnAction(e -> handleEditorUndo());
                if (editorRedoButton != null) editorRedoButton.setOnAction(e -> handleEditorRedo());
            }

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

            // --- Настройка кнопки внешнего открытия для вкладки Редактор ---
            if (openSchemeInExternalWindowButtonEditor != null) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    openSchemeInExternalWindowButtonEditor.setOnAction(event -> handleOpenSchemeInExternalWindowEditor());
                } else {
                    openSchemeInExternalWindowButtonEditor.setDisable(true);
                    openSchemeInExternalWindowButtonEditor.setText("Внешнее открытие не поддерживается");
                }
            }

            addToPdfLog("Запуск системы...");
            addToPdfLog("✓ Система инициализирована");

        } catch (Exception e) {
            e.printStackTrace();
            showErrorAlert("Ошибка инициализации приложения: " + e.getMessage());
        }
    }

    // --- Undo/Redo для вкладки Редактор ---
    private void pushEditorSnapshot() {
        try {
            Object jsonArr = editorWebView.getEngine().executeScript(String.join("\n",
                    "(function(){",
                    " try {",
                    "  var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                    "  if (!root) return '[]';",
                    "  var markers = root.querySelectorAll('[data-mid]');",
                    "  var arr = [];",
                    "  markers.forEach(function(m){",
                    "    arr.push({",
                    "      id: m.getAttribute('data-mid'),",
                    "      page: parseInt(m.getAttribute('data-page')||'1',10),",
                    "      x: parseFloat(m.getAttribute('data-x')||'0'),",
                    "      y: parseFloat(m.getAttribute('data-y')||'0'),",
                    "      size: parseFloat(m.getAttribute('data-size')||'16'),",
                    "      color: m.getAttribute('data-color')||'#FF0000'",
                    "    });",
                    "  });",
                    "  return JSON.stringify(arr);",
                    " } catch(e){ return '[]'; }",
                    "})();"
            ));
            if (jsonArr instanceof String) {
                editorUndoStack.push((String) jsonArr);
                editorRedoStack.clear();
            }
        } catch (Exception ignored) {}
    }

    @FXML
    private void handleEditorUndo() {
        try {
            if (editorUndoStack.isEmpty()) return;
            String current = getEditorSnapshot();
            String prev = editorUndoStack.pollLast();
            if (prev == null) return;
            if (current != null) editorRedoStack.push(current);
            restoreEditorSnapshot(prev);
            addToEditorLog("↶ Отмена последнего действия");
        } catch (Exception ex) { addToEditorLog("Ошибка Undo: " + ex.getMessage()); }
    }

    @FXML
    private void handleEditorRedo() {
        try {
            if (editorRedoStack.isEmpty()) return;
            String current = getEditorSnapshot();
            String next = editorRedoStack.pollLast();
            if (next == null) return;
            if (current != null) editorUndoStack.push(current);
            restoreEditorSnapshot(next);
            addToEditorLog("↷ Повтор действия");
        } catch (Exception ex) { addToEditorLog("Ошибка Redo: " + ex.getMessage()); }
    }

    private String getEditorSnapshot() {
        try {
            Object jsonArr = editorWebView.getEngine().executeScript(String.join("\n",
                    "(function(){",
                    " try {",
                    "  var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                    "  if (!root) return '[]';",
                    "  var markers = root.querySelectorAll('[data-mid]');",
                    "  var arr = [];",
                    "  markers.forEach(function(m){",
                    "    arr.push({",
                    "      id: m.getAttribute('data-mid'),",
                    "      page: parseInt(m.getAttribute('data-page')||'1',10),",
                    "      x: parseFloat(m.getAttribute('data-x')||'0'),",
                    "      y: parseFloat(m.getAttribute('data-y')||'0'),",
                    "      size: parseFloat(m.getAttribute('data-size')||'16'),",
                    "      color: m.getAttribute('data-color')||'#FF0000'",
                    "    });",
                    "  });",
                    "  return JSON.stringify(arr);",
                    " } catch(e){ return '[]'; }",
                    "})();"
            ));
            if (jsonArr instanceof String) return (String) jsonArr;
        } catch (Exception ignored) {}
        return null;
    }

    private void restoreEditorSnapshot(String json) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            ArmatureMarkerDTO[] list = mapper.readValue(json, ArmatureMarkerDTO[].class);
            editorWebView.getEngine().executeScript("if (window.clearEditorMarkers) window.clearEditorMarkers();");
            for (ArmatureMarkerDTO m : list) {
                String js = String.format(java.util.Locale.US,
                        "window.renderEditorMarker('%s', %d, %f, %f, %d, '%s');",
                        escapeJs(m.id), m.page, m.x, m.y, (int)Math.round(m.size), escapeJs(m.color));
                editorWebView.getEngine().executeScript(js);
            }
        } catch (Exception ex) {
            addToEditorLog("Ошибка восстановления состояния: " + ex.getMessage());
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
                    String jsonFilePath = resolveArmatureJsonPath().toString();
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

    private void setupEditorViewerHooks() {
        if (editorWebView == null) return;
        editorWebView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            try {
                String location = editorWebView.getEngine().getLocation();
                if (newState == javafx.concurrent.Worker.State.SUCCEEDED && location != null && location.contains("viewer.html")) {
                    JSObject window = (JSObject) editorWebView.getEngine().executeScript("window");
                    window.setMember("editorApp", new EditorAppBridge());
                    injectEditorClickCapture();
                    addToEditorLog("[Editor] JS-мост установлен и слушатель кликов подключен.");
                    // Доп. инициализация: функции управления режимом и привязка обработчиков
                    boolean sel = editorMarkToggle != null && editorMarkToggle.isSelected();
                    // Initial call to attach handlers and set marking state after viewer.html loads.
                    // The actual JS functions are now defined within injectEditorClickCapture.
                    // We re-run injectEditorClickCapture here to ensure all event listeners are set up,
                    // and then explicitly set the marking state based on the toggle button.
                    editorWebView.getEngine().executeScript("if (typeof window.__attachEditorHandler === 'function') { window.__attachEditorHandler(); }");
                    editorWebView.getEngine().executeScript("if (typeof window.__setMarking === 'function') { window.__setMarking(" + (sel ? "true" : "false") + "); }");
                }
            } catch (Exception ex) {
                addToEditorLog("Ошибка установки JS-моста в редакторе: " + ex.getMessage());
            }
        });
    }

    private void injectEditorClickCapture() {
        String js = String.join("\n",
                "(function(){",
                "  try {",
                "    var bind = function(){",
                "      var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                "      if (!root) { if (window.editorApp && editorApp.debug) editorApp.debug('bind: no root'); return false; }",
                "      if (root.__editorClickBound) { if (window.editorApp && editorApp.debug) editorApp.debug('bind: already'); return true; }",
                "      root.__editorClickBound = true;",
                "      try { root.style.pointerEvents='auto'; root.tabIndex = root.tabIndex || 0; } catch(e){}",
                "      window.__editorMarking = !!window.__editorMarking;",
                "      if (window.editorApp && editorApp.debug) editorApp.debug('bind: attached to '+ (root.id||'unknown'));",
                "      // Marker helpers",
                "      // Drag-n-drop state",
                "      if (!window.__dragState) window.__dragState = null;",
                "      if (!document.__dragHandlersInstalled){",
                "        document.addEventListener('mousemove', function(e){",
                "          if (!window.__dragState) return;",
                "          var st = window.__dragState;",
                "          try {",
                "            var rect = st.canvas.getBoundingClientRect();",
                "            var parentRect = st.parent.getBoundingClientRect();",
                "            var s = st.scale > 0 ? st.scale : 1.0;",
                "            var xPdf = (e.clientX - rect.left) / s;",
                "            var yPdf = (e.clientY - rect.top) / s;",
                "            st.xPdf = xPdf; st.yPdf = yPdf;",
                "            var drawW = parseFloat(getComputedStyle(st.marker).width)|| (st.size * s);",
                "            var half = drawW / 2;",
                "            var left = (rect.left - parentRect.left) + (xPdf * s) - half;",
                "            var top = (rect.top - parentRect.top) + (yPdf * s) - half;",
                "            st.marker.style.left = left + 'px';",
                "            st.marker.style.top = top + 'px';",
                "          } catch(err){}",
                "        }, true);",
                "        document.addEventListener('mouseup', function(e){",
                "          if (!window.__dragState) return;",
                "          var st = window.__dragState; window.__dragState = null;",
                "          try {",
                "            if (st.xPdf != null && st.yPdf != null){ ",
                "              var baseSize = parseFloat(st.marker.getAttribute('data-size')||String(st.size))||st.size;",
                "              var color = st.marker.getAttribute('data-color')||'#FF0000';",
                "              if (typeof window.renderEditorMarker==='function'){ window.renderEditorMarker(st.id, st.page, st.xPdf, st.yPdf, baseSize, color); }",
                "              if (window.editorApp && window.editorApp.onMarkerMoved){ window.editorApp.onMarkerMoved(st.id, st.xPdf, st.yPdf, st.page); }",
                "            }",
                "          } catch(err){}",
                "        }, true);",
                "        document.__dragHandlersInstalled = true;",
                "      }",
                "      window.__installDragHandlersForMarker = function(marker, id, pageNumber, size){",
                "        marker.addEventListener('mousedown', function(ev){",
                "          if (!window.__editorMarking) return;",
                "          if (ev && ev.button !== 0) return;",
                "          ev.preventDefault(); ev.stopPropagation();",
                "          var canvas = root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas');",
                "          if (!canvas) return;",
                "          var s = (typeof window.scale === 'number') ? window.scale : 1.0;",
                "          window.__dragState = { id: id, marker: marker, canvas: canvas, parent: canvas.parentElement || root, scale: s, size: size, page: pageNumber, xPdf: null, yPdf: null };",
                "        }, true);",
                "      };",
                "      window.renderEditorMarker = function(id, pageNumber, xPdf, yPdf, size, color){",
                "        try {",
                "          var canvas = root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas');",
                "          if (!canvas) return;",
                "          var parent = canvas.parentElement;",
                "          var s = (typeof window.scale === 'number') ? window.scale : 1.0;",
                "          var drawSize = size * s;",
                "          var selector = \"[data-mid='\" + id + \"']\";",
                "          var marker = parent.querySelector(selector);",
                "          if (!marker){",
                "            marker = document.createElement('div');",
                "            marker.setAttribute('data-mid', id);",
                "            marker.setAttribute('data-page', String(pageNumber));",
                "            marker.setAttribute('data-color', color);",
                "            marker.setAttribute('data-x', String(xPdf));",
                "            marker.setAttribute('data-y', String(yPdf));",
                "            marker.setAttribute('data-size', String(size));",
                "            marker.style.position = 'absolute';",
                "            marker.style.border = '2px solid ' + color;",
                "            marker.style.background = color + '22';",
                "            marker.style.width = drawSize + 'px';",
                "            marker.style.height = drawSize + 'px';",
                "            marker.style.transform = 'none';",
                "            marker.style.pointerEvents = 'auto';",
                "            marker.style.zIndex = '9999';",
                "            if (parent && !parent.__posRel){ parent.style.position = 'relative'; parent.__posRel = true; }",
                "            marker.addEventListener('click', function(ev){ ev.preventDefault(); ev.stopPropagation(); if(window.editorApp && editorApp.onMarkerClick){ editorApp.onMarkerClick(id); } });",
                "            // Context menu on marker for deletion when editing mode is ON",
                "            marker.addEventListener('contextmenu', function(ev){",
                "              if (!window.__editorMarking) return;",
                "              ev.preventDefault(); ev.stopPropagation();",
                "              if (window.editorApp && editorApp.onMarkerContextMenu){ editorApp.onMarkerContextMenu(id, (ev.screenX||0), (ev.screenY||0)); }",
                "            }, true);",
                "            window.__installDragHandlersForMarker(marker, id, pageNumber, size);",
                "            parent.appendChild(marker);",
                "          }",
                "          // Update attributes/styles for existing marker as well",
                "          marker.setAttribute('data-page', String(pageNumber));",
                "          marker.setAttribute('data-color', color);",
                "          marker.setAttribute('data-x', String(xPdf));",
                "          marker.setAttribute('data-y', String(yPdf));",
                "          marker.setAttribute('data-size', String(size));",
                "          marker.style.width = drawSize + 'px';",
                "          marker.style.height = drawSize + 'px';",
                "          marker.style.border = '2px solid ' + color;",
                "          marker.style.background = color + '22';",
                "          // Ensure contextmenu handler is present on existing markers too",
                "          if (!marker.__ctxBound){",
                "            marker.addEventListener('contextmenu', function(ev){",
                "              if (!window.__editorMarking) return;",
                "              ev.preventDefault(); ev.stopPropagation();",
                "              if (window.editorApp && editorApp.onMarkerContextMenu){ editorApp.onMarkerContextMenu(id, (ev.screenX||0), (ev.screenY||0)); }",
                "            }, true);",
                "            marker.__ctxBound = true;",
                "          }",
                "          var curPage = (typeof window.currentPage === 'number') ? window.currentPage : 1;",
                "          if (pageNumber !== curPage) { marker.style.display='none'; return; } else { marker.style.display='block'; }",
                "          var rect = canvas.getBoundingClientRect();",
                "          var parentRect = parent.getBoundingClientRect();",
                "          var left = (rect.left - parentRect.left) + (xPdf * s) - (drawSize / 2);",
                "          var top = (rect.top - parentRect.top) + (yPdf * s) - (drawSize / 2);",
                "          marker.style.left = left + 'px';",
                "          marker.style.top = top + 'px';",
                "        } catch(e){ if (window.editorApp && editorApp.debug) editorApp.debug('render error: '+e); }",
                "      };",
                "      window.removeEditorMarker = function(id){ try { var selector = \"[data-mid='\" + id + \"']\"; root.querySelectorAll(selector).forEach(function(n){ n.remove(); }); } catch(e){} };",
                "      window.clearEditorMarkers = function(){ try { root.querySelectorAll('[data-mid]').forEach(function(n){ n.remove(); }); } catch(e){} };",
                "      window.repositionEditorMarkers = function(){",
                "        try {",
                "          var canvas = root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas');",
                "          if (!canvas) return;",
                "          var parent = canvas.parentElement;",
                "          var s = (typeof window.scale === 'number') ? window.scale : 1.0;",
                "          var rect = canvas.getBoundingClientRect();",
                "          var parentRect = parent.getBoundingClientRect();",
                "          var curPage = (typeof window.currentPage === 'number') ? window.currentPage : 1;",
                "          root.querySelectorAll('[data-mid]').forEach(function(m){",
                "            var page = parseInt(m.getAttribute('data-page')||'1',10);",
                "            var baseSize = parseFloat(m.getAttribute('data-size')||'16');",
                "            var size = baseSize * s;",
                "            if (page !== curPage) { m.style.display='none'; return; } else { m.style.display='block'; }",
                "            var xPdf = parseFloat(m.getAttribute('data-x')||'0');",
                "            var yPdf = parseFloat(m.getAttribute('data-y')||'0');",
                "            m.style.width = size + 'px';",
                "            m.style.height = size + 'px';",
                "            var left = (rect.left - parentRect.left) + (xPdf * s) - (size/2);",
                "            var top = (rect.top - parentRect.top) + (yPdf * s) - (size/2);",
                "            m.style.left = left + 'px';",
                "            m.style.top = top + 'px';",
                "          });",
                "        } catch(e){}",
                "      };",
                "      var handleClick = function(ev){",
                "        try {",
                "          if (window.editorApp && editorApp.debug) editorApp.debug('click captured');",
                "          var inMarker = ev.target && ev.target.closest && ev.target.closest('[data-mid]');",
                "          if (inMarker) { ev.preventDefault(); ev.stopPropagation(); return; }",
                "          if (!window.__editorMarking) { if (window.editorApp && editorApp.debug) editorApp.debug('click ignored: marking OFF'); return; }",
                "          if (!window.__addingMode) { if (window.editorApp && editorApp.debug) editorApp.debug('click ignored: addingMode OFF'); return; }",
                "          var canvas = root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas');",
                "          if (!canvas) { if (window.editorApp && editorApp.debug) editorApp.debug('no canvas'); return; }",
                "          var rect = canvas.getBoundingClientRect();",
                "          var s = (typeof window.scale === 'number') ? window.scale : 1.0;",
                "          var xPdf = (ev.clientX - rect.left) / s;",
                "          var yPdf = (ev.clientY - rect.top) / s;",
                "          var page = (typeof window.currentPage === 'number') ? window.currentPage : 1;",
                "          if (window.editorApp && typeof window.editorApp.onPdfClick === 'function') { window.editorApp.onPdfClick(xPdf, yPdf, page, s); }",
                "        } catch(e){ if (window.editorApp && editorApp.debug) editorApp.debug('click error: '+e); }",
                "      };",
                "      // root.addEventListener('click', handleClick, true); // use __attachEditorHandler-managed handler only",
                "      // Global document-level safety net (in case root handler misses)",
                "      try { if (document.__editorGlobalHandler) { document.removeEventListener('click', document.__editorGlobalHandler, true); } } catch(e){}",
                "      document.__editorGlobalHandler = function(ev){",
                "        try {",
                "          if (!window.__editorMarking) return;",
                "          if (!window.__addingMode) return;",
                "          var inMarker = ev.target && ev.target.closest && ev.target.closest('[data-mid]'); if (inMarker) return;",
                "          var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                "          var canvas = root && (root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas'));",
                "          if (!canvas) return;",
                "          var rect = canvas.getBoundingClientRect();",
                "          var s = (typeof window.scale === 'number') ? window.scale : 1.0;",
                "          var xPdf = (ev.clientX - rect.left) / s;",
                "          var yPdf = (ev.clientY - rect.top) / s;",
                "          var page = (typeof window.currentPage === 'number') ? window.currentPage : 1;",
                "          if (window.editorApp && typeof window.editorApp.onPdfClick === 'function') { window.editorApp.onPdfClick(xPdf, yPdf, page, s); }",
                "        } catch(e) { if (window.editorApp && editorApp.debug) editorApp.debug('doc handler error:'+e); }",
                "      };",
                "      document.addEventListener('click', document.__editorGlobalHandler, true);",
                "      // Context menu to force-add marker at clicked point",
                "      try { if (root.__editorCtxHandler) { root.removeEventListener('contextmenu', root.__editorCtxHandler, true); } } catch(e){}",
                "      root.__editorCtxHandler = function(ev){",
                "        try {",
                "          var canvas = null;",
                "          var target = ev.target;",
                "          while (target && target !== root) {",
                "            if (target.matches('.pdf-page') || (target.tagName && target.tagName.toLowerCase() === 'canvas')) { canvas = target; break; }",
                "            target = target.parentElement;",
                "          }",
                "          if (!canvas) { canvas = root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas'); }",
                "          if (!canvas) return;",
                "          var rect = canvas.getBoundingClientRect();",
                "          var s = (typeof window.scale === 'number') ? window.scale : 1.0;",
                "          var xPdf = (ev.clientX - rect.left) / s;",
                "          var yPdf = (ev.clientY - rect.top) / s;",
                "          var page = (typeof window.currentPage === 'number') ? window.currentPage : 1;",
                "          if (window.editorApp && typeof window.editorApp.onCanvasContextMenu === 'function') {",
                "            var scrX = (typeof ev.screenX === 'number') ? ev.screenX : 0;",
                "            var scrY = (typeof ev.screenY === 'number') ? ev.screenY : 0;",
                "            window.editorApp.onCanvasContextMenu(xPdf, yPdf, page, s, scrX, scrY);",
                "          }",
                "          ev.preventDefault(); ev.stopPropagation();",
                "        } catch(e) { if (window.editorApp && editorApp.debug) editorApp.debug('ctx error:'+e); }",
                "      };",
                "      root.addEventListener('contextmenu', root.__editorCtxHandler, true);",
                "      // Детальное логирование",
                "      var logEvt = function(name, ev){ if (window.editorApp && editorApp.debug) editorApp.debug(name+': '+(ev.target && ev.target.tagName)+' @'+ev.clientX+','+ev.clientY+' marking='+window.__editorMarking); };",
                "      root.addEventListener('mousedown', function(e){ logEvt('mousedown', e); }, true);",
                "      root.addEventListener('mouseup', function(e){ logEvt('mouseup', e); }, true);",
                "      // Track last client coordinates for FX context menu mapping",
                "      document.addEventListener('mousemove', function(e){ window.__lastClientX = e.clientX; window.__lastClientY = e.clientY; }, true);",
                "      document.addEventListener('mousedown', function(e){ window.__lastClientX = e.clientX; window.__lastClientY = e.clientY; }, true);",
                "      root.addEventListener('wheel', function(e){ if (window.editorApp && editorApp.debug) editorApp.debug('wheel: delta='+e.deltaY); }, {passive:true, capture:true});",
                "      return true;",
                "    };",
                "    if (!bind()) {",
                "      if (!window.__editorBindObserver) {",
                "        window.__editorBindObserver = new MutationObserver(function(){ try { if (bind() && window.__editorBindObserver) { window.__editorBindObserver.disconnect(); window.__editorBindObserver = null; if (window.editorApp && editorApp.debug) editorApp.debug('observer: bound and stopped'); } } catch(e){} });",
                "        window.__editorBindObserver.observe(document, {childList:true, subtree:true});",
                "        if (window.editorApp && editorApp.debug) editorApp.debug('observer: started');",
                "      }",
                "    }",
                "  } catch(e){ if (window.editorApp && editorApp.debug) editorApp.debug('inject error: '+e); }",
                "})();",
                "",
                "// --- Setup initial marking state and attach handlers --- ",
                "if (window.editorApp && editorApp.debug) editorApp.debug('injectEditorClickCapture: defining shared functions');",
                "window.__setMarking = function(v){ ",
                "  window.__editorMarking = !!v; ",
                "  try{ sessionStorage.setItem('__editorMarking', String(!!v)); }catch(e){} ",
                "  if (window.editorApp && editorApp.debug) editorApp.debug('setMarking:'+v + ' current=' + window.__editorMarking); ",
                "};",
                "window.__setAddingMode = function(v){ ",
                "  window.__addingMode = !!v; ",
                "  try{ sessionStorage.setItem('__addingMode', String(!!v)); }catch(e){} ",
                "  if (window.editorApp && editorApp.debug) editorApp.debug('setAddingMode:'+v); ",
                "};",
                "window.__attachEditorHandler = function(){",
                "  var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                "  if (!root) { if (window.editorApp && editorApp.debug) editorApp.debug('attach: no root'); return; }",
                "  try { root.style.pointerEvents='auto'; root.tabIndex = root.tabIndex || 0; } catch(e){}",
                "  if (root.__editorHandler) { try { root.removeEventListener('click', root.__editorHandler, true); if (window.editorApp && editorApp.debug) editorApp.debug('attach: removed old handler'); } catch(e){} }",
                "  var handler = function(ev){ ",
                "    try { ",
                "      if (window.editorApp && editorApp.debug) editorApp.debug('handler: click caught marking=' + window.__editorMarking + ' target=' + (ev.target && ev.target.tagName)); ",
                "      if (!window.__editorMarking) return; ",
                "      if (!window.__addingMode) return; ",
                "      var inMarker = ev.target && ev.target.closest && ev.target.closest('[data-mid]'); if (inMarker) return; ",
                "      var canvas = null;",
                "      var target = ev.target;",
                "      while (target && target !== root) {",
                "          if (target.matches('.pdf-page') || (target.tagName.toLowerCase() === 'canvas')) {",
                "              canvas = target;",
                "              break;",
                "          }",
                "          target = target.parentElement;",
                "      }",
                "      if (!canvas) {",
                "          canvas = root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas');",
                "      }",
                "      if (!canvas) { if (window.editorApp && editorApp.debug) editorApp.debug('handler: no valid canvas target found'); return; } ",
                "      var rect = canvas.getBoundingClientRect(); ",
                "      var s = (typeof window.scale==='number')?window.scale:1.0; ",
                "      var xPdf=(ev.clientX-rect.left)/s; var yPdf=(ev.clientY-rect.top)/s; ",
                "      var page=(typeof window.currentPage==='number')?window.currentPage:1; ",
                "      if (window.editorApp && window.editorApp.onPdfClick) { ",
                "        if (window.editorApp && editorApp.debug) editorApp.debug('handler: calling onPdfClick: ' + xPdf + ',' + yPdf + ',' + page + ',' + s); ",
                "        window.editorApp.onPdfClick(xPdf,yPdf,page,s); ",
                "      } ",
                "    } catch(e){ if (window.editorApp && editorApp.debug) editorApp.debug('handler error:'+e); } ",
                "  }; ",
                "  root.__editorHandler = handler; ",
                "  root.addEventListener('click', handler, true); ",
                "  if (window.editorApp && editorApp.debug) editorApp.debug('attach: bound new handler'); ",
                "};",
                "",
                "// Hook into PDF.js eventBus if available",
                "try {",
                "  if (window.PDFViewerApplication && PDFViewerApplication.eventBus) {",
                "    var eb = window.PDFViewerApplication.eventBus;",
                "    eb.on('pagesloaded', function(){ if (window.editorApp && editorApp.debug) editorApp.debug('eventBus: pagesloaded'); if (typeof window.__attachEditorHandler==='function') window.__attachEditorHandler(); if (window.repositionEditorMarkers) window.repositionEditorMarkers(); });",
                "    eb.on('pagerendered', function(){ if (typeof window.__attachEditorHandler==='function') window.__attachEditorHandler(); if (window.repositionEditorMarkers) window.repositionEditorMarkers(); });",
                "    eb.on('scalechanged', function(){ if (window.editorApp && editorApp.debug) editorApp.debug('eventBus: scalechanged'); if (typeof window.__attachEditorHandler==='function') window.__attachEditorHandler(); if (window.repositionEditorMarkers) window.repositionEditorMarkers(); });",
                "    if (typeof eb.on === 'function') { try { eb.on('pagechanging', function(){ if (window.repositionEditorMarkers) window.repositionEditorMarkers(); }); } catch(e){} }",
                "    if (window.editorApp && editorApp.debug) editorApp.debug('eventBus: hooked');",
                "  } else { if (window.editorApp && editorApp.debug) editorApp.debug('eventBus not ready'); }",
                "} catch(e){ if (window.editorApp && editorApp.debug) editorApp.debug('eventBus hook error:'+e); }",
                "",
                "// Fallback poller to ensure late binding",
                "if (!window.__editorBindPoller){ ",
                "  var __tries=0; ",
                "  window.__editorBindPoller = setInterval(function(){ ",
                "    __tries++; ",
                "    try { ",
                "      if (typeof window.__attachEditorHandler==='function') window.__attachEditorHandler(); ",
                "      var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer'); ",
                "      var canvas = root ? (root.querySelector('.pdf-page') || root.querySelector('.canvasWrapper canvas') || root.querySelector('canvas')) : null; ",
                "      if (canvas){ ",
                "        clearInterval(window.__editorBindPoller); ",
                "        window.__editorBindPoller=null; ",
                "        if (window.repositionEditorMarkers) window.repositionEditorMarkers(); ",
                "        if (window.editorApp && editorApp.debug) editorApp.debug('poller: bound and stopped'); ",
                "      } ",
                "      if (__tries>40){ ",
                "        clearInterval(window.__editorBindPoller); ",
                "        window.__editorBindPoller=null; ",
                "        if (window.editorApp && editorApp.debug) editorApp.debug('poller: gave up'); ",
                "      } ",
                "    } catch(e){} ",
                "  }, 100); ",
                "  if (window.editorApp && editorApp.debug) editorApp.debug('poller: started'); ",
                "}",
                "// Initial binding and state restoration immediately after script injection",
                "try {",
                "  if (typeof window.__attachEditorHandler === 'function') { window.__attachEditorHandler(); }",
                "  // Restore marking state from session storage on initial load of viewer.html",
                "  var savedMarking = sessionStorage.getItem('__editorMarking');",
                "  if (savedMarking !== null) { ",
                "    window.__setMarking(savedMarking === 'true'); ",
                "    if (window.editorApp && editorApp.debug) editorApp.debug('Restored marking state from sessionStorage: ' + (savedMarking === 'true')); ",
                "  } else {",
                "    // If no saved state, use the initial JavaFX toggle state (which will be handled by the toggle listener on load)",
                "    if (window.editorApp && editorApp.debug) editorApp.debug('No saved marking state found. Will use JavaFX toggle state.');",
                "  }",
                "  if (window.repositionEditorMarkers) window.repositionEditorMarkers();",
                "  window.addEventListener('resize', function(){ if (window.repositionEditorMarkers) window.repositionEditorMarkers(); }, true);",
                "  var vc = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                "  if (vc) vc.addEventListener('scroll', function(){ if (window.repositionEditorMarkers) window.repositionEditorMarkers(); }, true);",
                "} catch(e){ if (window.editorApp && editorApp.debug) editorApp.debug('initial binding/state error: '+e); }"
        );
        editorWebView.getEngine().executeScript(js);
        if (editorMarkToggle != null) {
            editorMarkToggle.selectedProperty().addListener((obs, ov, nv) -> {
                Platform.runLater(() -> {
                    try {
                        editorWebView.getEngine().executeScript(String.format("if (typeof window.__setMarking === 'function') { window.__setMarking(%b); }", nv));
                        editorWebView.getEngine().executeScript("(function(){ try { var root=document.getElementById('viewerContainer')||document.getElementById('pdfContainer'); if(root && root.__editorHandler){ root.removeEventListener('click', root.__editorHandler, true); root.__editorHandler=null; } } catch(e){} })();");
                        editorWebView.getEngine().executeScript("if (typeof window.__setAddingMode === 'function') { window.__setAddingMode(false); }");
                        editorWebView.getEngine().executeScript(String.format("if (window.editorApp && editorApp.debug) editorApp.debug('toggle applied: %b mark now=' + window.__editorMarking);", nv));
                        // Re-run injectEditorClickCapture to ensure all setup (including mutation observer) is active
                        // and event handlers are re-bound if needed, when marking is turned ON.
                        if (nv) {
                            addToEditorLog("Calling injectEditorClickCapture on toggle ON");
                            injectEditorClickCapture();
                        }
                        addToEditorLog("Toggle changed → " + (nv ? "ON" : "OFF"));
                        addToEditorLog(nv ? "Режим разметки: ВКЛ" : "Режим разметки: ВЫКЛ");
                        if (nv) {
                            editorWebView.requestFocus();
                        }
                    } catch (Exception e) {
                        addToEditorLog("Ошибка в слушателе ToggleButton: " + e.getMessage());
                    }
                });
            });
            // Initial set of the marking state when the controller initializes and after JS is injected
            Platform.runLater(() -> {
                boolean sel = editorMarkToggle.isSelected();
                // The initial marking state is now primarily handled by the JS code itself via sessionStorage
                // or by the default in injectEditorClickCapture. We only explicitly set it here if no saved state exists.
                // This call ensures the JavaFX toggle state is reflected in JS if no prior state was restored.
                editorWebView.getEngine().executeScript(String.format("if (typeof window.__setMarking === 'function') { window.__setMarking(%b); }", sel));
                editorWebView.getEngine().executeScript("if (typeof window.__setAddingMode === 'function') { window.__setAddingMode(false); }");
                addToEditorLog(sel ? "Режим разметки: ВКЛ" : "Режим разметки: ВЫКЛ");
            });
        }
    }

    public class EditorAppBridge {
        public void onPdfClick(double x, double y, int page, double scale) {
            Platform.runLater(() -> {
                if (editorDialogOpen) {
                    addToEditorLog("Диалог уже открыт — клик игнорируется.");
                    return;
                }
                addToEditorLog(String.format(java.util.Locale.US,
                        "Клик: page=%d, x=%.2f, y=%.2f, scale=%.2f", page, x, y, scale));
                showEditorAnnotationDialogAndSave(x, y, page, scale);
            });
        }
        public void debug(String msg) {
            Platform.runLater(() -> addToEditorLog("JS: " + msg));
        }
        public void onMarkerClick(String id) {
            Platform.runLater(() -> {
                ContextMenu menu = new ContextMenu();
                MenuItem edit = new MenuItem("Редактировать");
                MenuItem del = new MenuItem("Удалить");
                edit.setOnAction(a -> editEditorMarker(id));
                del.setOnAction(a -> {
                    try { editorWebView.getEngine().executeScript("window.removeEditorMarker('" + escapeJs(id) + "');"); } catch (Exception ignored) {}
                    editorDeletedMarkerIds.add(id);
                    addToEditorLog("✓ Метка помечена на удаление: " + id);
                });
                menu.getItems().addAll(edit, del);
                menu.show(editorWebView, javafx.geometry.Side.TOP, 0, 0);
            });
        }
        public void onMarkerContextMenu(String id, double screenX, double screenY) {
            Platform.runLater(() -> {
                try {
                    if (editorMarkToggle == null || !editorMarkToggle.isSelected()) return;
                    if (editorActiveContextMenu != null && editorActiveContextMenu.isShowing()) {
                        editorActiveContextMenu.hide();
                        editorActiveContextMenu = null;
                    }
                    ContextMenu menu = new ContextMenu();
                    MenuItem del = new MenuItem("Удалить точку");
                    del.setOnAction(a -> { pushEditorSnapshot(); deleteEditorMarker(id); });
                    menu.getItems().add(del);
                    if (editorWebView != null && editorWebView.getScene() != null && editorWebView.getScene().getWindow() != null) {
                        menu.show(editorWebView.getScene().getWindow(), screenX, screenY);
                    } else {
                        menu.show(editorWebView, javafx.geometry.Side.TOP, 0, 0);
                    }
                    editorActiveContextMenu = menu;
                } catch (Exception ex) {
                    addToEditorLog("Ошибка показа контекстного меню метки: " + ex.getMessage());
                }
            });
        }
        public void onMarkerMoved(String id, double x, double y, int page){
            Platform.runLater(() -> {
                try {
                    pushEditorSnapshot();
                    String jsRepaint = String.join("\n",
                            "(function(){",
                            " var root=document.getElementById('viewerContainer')||document.getElementById('pdfContainer'); if(!root) return;",
                            " var m=root.querySelector(\"[data-mid='" + escapeJs(id) + "']\"); if(!m) return;",
                            " m.setAttribute('data-x', '" + String.format(java.util.Locale.US, "%f", x) + "');",
                            " m.setAttribute('data-y', '" + String.format(java.util.Locale.US, "%f", y) + "');",
                            " m.setAttribute('data-page', '" + page + "');",
                            " var baseSize=parseFloat(m.getAttribute('data-size')||'16');",
                            " var color=m.getAttribute('data-color')||'#FF0000';",
                            " if (window.renderEditorMarker) window.renderEditorMarker('" + escapeJs(id) + "', " + page + ", " + String.format(java.util.Locale.US, "%f", x) + ", " + String.format(java.util.Locale.US, "%f", y) + ", baseSize, color);",
                            "})();");
                    editorWebView.getEngine().executeScript(jsRepaint);
                    addToEditorLog("✓ Метка перемещена (на экране): " + id + String.format(java.util.Locale.US, " → page=%d x=%.2f y=%.2f", page, x, y));
                } catch (Exception ex) {
                    addToEditorLog("Ошибка перемещения метки: " + ex.getMessage());
                }
            });
        }
        public void onCanvasContextMenu(double x, double y, int page, double scale, double screenX, double screenY) {
            Platform.runLater(() -> {
                try {
                    ContextMenu menu = new ContextMenu();
                    MenuItem addHere = new MenuItem("Отметить точку здесь…");
                    addHere.setOnAction(a -> showEditorAnnotationDialogAndSave(x, y, page, scale));
                    menu.getItems().add(addHere);
                    if (editorWebView != null && editorWebView.getScene() != null && editorWebView.getScene().getWindow() != null) {
                        menu.show(editorWebView.getScene().getWindow(), screenX, screenY);
                    } else {
                        menu.show(editorWebView, javafx.geometry.Side.TOP, 0, 0);
                    }
                } catch (Exception ex) {
                    addToEditorLog("Ошибка показа контекстного меню: " + ex.getMessage());
                }
            });
        }
    }

    private void showEditorAnnotationDialogAndSave(double x, double y, int page, double scale) {
        if (editorCurrentPdfFileName == null || editorCurrentPdfFileName.isEmpty()) {
            showErrorAlert("Не загружен PDF в редакторе. Сначала откройте схему во вкладке 'Редактор'.");
            return;
        }
        if (editorDialogOpen) {
            addToEditorLog("Диалог уже открыт — повторный показ заблокирован.");
            return;
        }
        editorDialogOpen = true;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Добавить отметку на схеме");
        dialog.setHeaderText("Укажите параметры отметки");

        Label nameLabel = new Label("Обозначение:");
        TextField nameField = new TextField();
        nameField.setPromptText("Например: AR-101");

        Label sizeLabel = new Label("Размер квадрата (px):");
        Spinner<Integer> sizeSpinner = new Spinner<>(4, 200, 16, 1);

        Label colorLabel = new Label("Цвет:");
        ColorPicker colorPicker = new ColorPicker(Color.RED);

        Label zoomLabel = new Label("Зум при переходе:");
        Spinner<Double> zoomSpinner = new Spinner<>(0.25, 5.0, Math.max(1.0, scale), 0.25);
        zoomSpinner.setEditable(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(nameLabel, 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(sizeLabel, 0, 1);
        grid.add(sizeSpinner, 1, 1);
        grid.add(colorLabel, 0, 2);
        grid.add(colorPicker, 1, 2);
        grid.add(zoomLabel, 0, 3);
        grid.add(zoomSpinner, 1, 3);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        // Валидация перед закрытием диалога по ОК
        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            try {
                String proposedId = nameField.getText() != null ? nameField.getText().trim() : "";
                if (proposedId.isEmpty()) {
                    showErrorAlert("Не указано обозначение арматуры.");
                    ev.consume();
                    return;
                }
                // Проверка дубликата: JSON + DOM
                boolean duplicate = false;
                try {
                    Path jp = resolveArmatureJsonPath();
                    if (Files.exists(jp) && editorCurrentPdfFileName != null) {
                        ObjectMapper mapper = new ObjectMapper();
                        Map<String, Map<String, ArmatureCoords>> all = mapper.readValue(jp.toFile(), new TypeReference<Map<String, Map<String, ArmatureCoords>>>() {});
                        Map<String, ArmatureCoords> perPdf = all != null ? all.get(editorCurrentPdfFileName) : null;
                        if (perPdf != null && perPdf.containsKey(proposedId)) duplicate = true;
                    }
                } catch (Exception ignored) {}
                try {
                    Object existsObj = editorWebView.getEngine().executeScript(String.join("\n",
                            "(function(){",
                            "  try {",
                            "    var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                            "    if (!root) return false;",
                            "    var sel = \"[data-mid='" + escapeJs(proposedId) + "']\";",
                            "    return !!root.querySelector(sel);",
                            "  } catch(e){ return false; }",
                            "})();"
                    ));
                    if (existsObj instanceof Boolean && ((Boolean) existsObj)) duplicate = true;
                } catch (Exception ignored) {}
                if (duplicate) {
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("Добавление точки");
                    a.setHeaderText(null);
                    a.setContentText("Такая точка уже создана");
                    a.showAndWait();
                    ev.consume();
                    return;
                }
                // Проверка наличия в списке Арматура
                boolean existsInTable = false;
                if (armatureTable != null && armatureTable.getItems() != null) {
                    for (RowDataDynamic row : armatureTable.getItems()) {
                        String n = row.getProperty("Арматура").get();
                        if (n != null && n.trim().equals(proposedId)) { existsInTable = true; break; }
                    }
                }
                if (!existsInTable) {
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("Добавление точки");
                    a.setHeaderText(null);
                    a.setContentText("Такой арматуры нет в списке");
                    a.showAndWait();
                    ev.consume();
                }
            } catch (Exception ignored) {}
        });

        // Предпросмотр: временный маркер в месте клика
        Runnable renderPreview = () -> {
            try {
                int sz = sizeSpinner.getValue();
                String hex = toHex(colorPicker.getValue());
                String jsPrev = String.format(java.util.Locale.US,
                        "window.renderEditorMarker('%s', %d, %f, %f, %d, '%s');",
                        EDITOR_PREVIEW_MARKER_ID, page, x, y, sz, escapeJs(hex));
                editorWebView.getEngine().executeScript(jsPrev);
            } catch (Exception ignored) {}
        };
        sizeSpinner.valueProperty().addListener((obs, ov, nv) -> renderPreview.run());
        colorPicker.valueProperty().addListener((obs, ov, nv) -> renderPreview.run());
        renderPreview.run();

        dialog.setResultConverter(bt -> bt);
        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK) {
            editorWebView.getEngine().executeScript("window.removeEditorMarker('" + EDITOR_PREVIEW_MARKER_ID + "');");
            addToEditorLog("Добавление отметки отменено.");
            editorDialogOpen = false;
            return;
        }

        String armatureId = nameField.getText() != null ? nameField.getText().trim() : "";

        int size = sizeSpinner.getValue();
        Color color = colorPicker.getValue();
        double zoom = zoomSpinner.getValue();

        // Не сохраняем сразу — только рисуем на схеме, сохранит пользователь кнопкой "Сохранить изменения"
        try { editorWebView.getEngine().executeScript("window.removeEditorMarker('" + EDITOR_PREVIEW_MARKER_ID + "');"); } catch (Exception ignored) {}
        String js = String.format(java.util.Locale.US,
                "window.renderEditorMarker('%s', %d, %f, %f, %d, '%s');",
                escapeJs(armatureId), page, x, y, size, escapeJs(toHex(color))
        );
        editorWebView.getEngine().executeScript(js);
        // Снимок состояния для undo
        pushEditorSnapshot();
        addToEditorLog("✓ Отметка добавлена на схему (ожидает сохранения): " + armatureId);
        editorDialogOpen = false;
    }

    private static String toHex(Color color) {
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private void saveEditorArmatureCoords(String pdfFileName, String armatureId, ArmatureCoords coords) throws IOException {
        Path jsonPath = resolveArmatureJsonPath();
        Files.createDirectories(jsonPath.getParent());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Map<String, ArmatureCoords>> allCoords = null;
        if (Files.exists(jsonPath)) {
            allCoords = mapper.readValue(jsonPath.toFile(), new TypeReference<Map<String, Map<String, ArmatureCoords>>>() {});
        }
        if (allCoords == null) {
            allCoords = new java.util.LinkedHashMap<>();
        }

        Map<String, ArmatureCoords> perPdf = allCoords.get(pdfFileName);
        if (perPdf == null) {
            perPdf = new java.util.LinkedHashMap<>();
            allCoords.put(pdfFileName, perPdf);
        }

        perPdf.put(armatureId, coords);
        mapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), allCoords);
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
    }

    private void renderEditorMarkersForCurrentPdf() throws IOException {
        if (editorCurrentPdfFileName == null || editorCurrentPdfFileName.isEmpty()) return;
        Path jsonPath = resolveArmatureJsonPath();
        if (!Files.exists(jsonPath)) return;
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Map<String, ArmatureCoords>> allCoords = mapper.readValue(jsonPath.toFile(), new TypeReference<Map<String, Map<String, ArmatureCoords>>>() {});
        Map<String, ArmatureCoords> perPdf = allCoords != null ? allCoords.get(editorCurrentPdfFileName) : null;
        if (perPdf == null || perPdf.isEmpty()) return;
        editorWebView.getEngine().executeScript("window.clearEditorMarkers && window.clearEditorMarkers();");
        for (Map.Entry<String, ArmatureCoords> e : perPdf.entrySet()) {
            ArmatureCoords c = e.getValue();
            String id = e.getKey();
            int size = (int) Math.round(c.getWidth());
            String color = "#FF0000";
            if (c.getMarker_type() != null && c.getMarker_type().startsWith("square:")) {
                String[] parts = c.getMarker_type().split(":");
                if (parts.length >= 2) color = parts[1];
            }
            String js = String.format(java.util.Locale.US,
                    "window.renderEditorMarker('%s', %d, %f, %f, %d, '%s');",
                    escapeJs(id), c.getPage(), c.getX(), c.getY(), size, escapeJs(color)
            );
            editorWebView.getEngine().executeScript(js);
        }
    }

    private void editEditorMarker(String id) {
        try {
            Path jsonPath = resolveArmatureJsonPath();
            if (!Files.exists(jsonPath) || editorCurrentPdfFileName == null) return;
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String, ArmatureCoords>> all = mapper.readValue(jsonPath.toFile(), new TypeReference<Map<String, Map<String, ArmatureCoords>>>() {});
            Map<String, ArmatureCoords> perPdf = all.get(editorCurrentPdfFileName);
            if (perPdf == null) return;
            ArmatureCoords c = perPdf.get(id);
            if (c == null) return;
            showEditorEditDialog(id, c);
        } catch (Exception ex) {
            addToEditorLog("Ошибка редактирования: " + ex.getMessage());
        }
    }

    private void deleteEditorMarker(String id) {
        try {
            // Удаляем только с экрана и помечаем к удалению. JSON/Excel чистим при сохранении
            editorDeletedMarkerIds.add(id);
            try { editorWebView.getEngine().executeScript("window.removeEditorMarker('" + id.replace("'", "\\'") + "');"); } catch (Exception ignored) {}
            addToEditorLog("✓ Метка удалена с экрана и помечена к удалению: " + id);
        } catch (Exception ex) {
            addToEditorLog("Ошибка удаления: " + ex.getMessage());
        }
    }

    private void showEditorEditDialog(String oldId, ArmatureCoords existing) {
        if (existing == null) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Редактировать отметку");
        dialog.setHeaderText("Измените параметры отметки");

        Label nameLabel = new Label("Обозначение:");
        TextField nameField = new TextField(oldId);

        int currentSize = (int) Math.round(existing.getWidth());
        String currentColor = extractColorFromMarkerType(existing.getMarker_type());
        double currentZoom = existing.getZoom() > 0 ? existing.getZoom() : 1.0;

        Label sizeLabel = new Label("Размер квадрата (px):");
        Spinner<Integer> sizeSpinner = new Spinner<>(4, 200, currentSize > 0 ? currentSize : 16, 1);

        Label colorLabel = new Label("Цвет:");
        ColorPicker colorPicker = new ColorPicker(parseHexColor(currentColor));

        Label zoomLabel = new Label("Зум при переходе:");
        Spinner<Double> zoomSpinner = new Spinner<>(0.25, 5.0, currentZoom, 0.25);
        zoomSpinner.setEditable(true);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.add(nameLabel, 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(sizeLabel, 0, 1);
        grid.add(sizeSpinner, 1, 1);
        grid.add(colorLabel, 0, 2);
        grid.add(colorPicker, 1, 2);
        grid.add(zoomLabel, 0, 3);
        grid.add(zoomSpinner, 1, 3);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ButtonType result = dialog.showAndWait().orElse(ButtonType.CANCEL);
        if (result != ButtonType.OK) {
            addToEditorLog("Редактирование отменено.");
            return;
        }

        String newId = nameField.getText() != null ? nameField.getText().trim() : oldId;
        int size = sizeSpinner.getValue();
        Color color = colorPicker.getValue();
        double zoom = zoomSpinner.getValue();

        // Обновляем существующий объект (координаты/страница остаются прежними)
        existing.setWidth(size);
        existing.setHeight(size);
        existing.setZoom(zoom);
        existing.setLabel(newId);
        existing.setMarker_type("square:" + toHex(color) + ":" + size);

        try {
            Path jsonPath = resolveArmatureJsonPath();
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String, ArmatureCoords>> all = mapper.readValue(jsonPath.toFile(), new TypeReference<Map<String, Map<String, ArmatureCoords>>>() {});
            Map<String, ArmatureCoords> perPdf = all.get(editorCurrentPdfFileName);
            if (perPdf == null) perPdf = new java.util.LinkedHashMap<>();

            if (!oldId.equals(newId)) {
                perPdf.remove(oldId);
            }
            perPdf.put(newId, existing);
            all.put(editorCurrentPdfFileName, perPdf);
            mapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), all);

            // Перерисовываем маркер(ы)
            if (!oldId.equals(newId)) {
                editorWebView.getEngine().executeScript("window.removeEditorMarker('" + escapeJs(oldId) + "');");
            }
            String js = String.format(java.util.Locale.US,
                    "window.renderEditorMarker('%s', %d, %f, %f, %d, '%s');",
                    escapeJs(newId), existing.getPage(), existing.getX(), existing.getY(), size, escapeJs(toHex(color))
            );
            editorWebView.getEngine().executeScript(js);
            addToEditorLog("✓ Отметка обновлена: " + oldId + " → " + newId);
        } catch (Exception ex) {
            showErrorAlert("Ошибка сохранения изменений: " + ex.getMessage());
        }
    }

    private static String extractColorFromMarkerType(String markerType) {
        try {
            if (markerType == null) return "#FF0000";
            if (!markerType.startsWith("square:")) return "#FF0000";
            String[] parts = markerType.split(":");
            if (parts.length >= 2) return parts[1];
        } catch (Exception ignored) {}
        return "#FF0000";
    }

    private static Color parseHexColor(String hex) {
        try {
            return Color.web(hex);
        } catch (Exception e) {
            return Color.RED;
        }
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

    @FXML
    private void handleOpenPdfSchemeEditor() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Открыть файл схемы PDF (Редактор)");
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
                addToEditorLog("Схема (редактор) успешно скопирована: " + targetPath.getFileName());

                if (tabPane != null && editorTab != null) {
                    tabPane.getSelectionModel().select(editorTab);
                }

                editorCurrentPdfFileName = targetPath.getFileName().toString();
                editorDeletedMarkerIds.clear();
                editorPdfManager.loadPdf(targetPath.toString(), null);
            } catch (IOException e) {
                showErrorAlert("Ошибка при открытии файла (Редактор): " + e.getMessage());
            }
        }
    }

    @FXML
    private void handleOpenSchemeInExternalWindowEditor() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Выбрать схему PDF для внешнего открытия (Редактор)");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

        File initialDir = new File(DATA_BASE_DIRECTORY + File.separator + PDFViewerManager.SCHEMES_SUBFOLDER);
        if (initialDir.exists() && initialDir.isDirectory()) {
            fileChooser.setInitialDirectory(initialDir);
        } else {
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
            addToEditorLog("Предупреждение: директория схем не найдена: " + initialDir.getAbsolutePath() + ". Открытие FileChooser в домашней директории.");
        }

        File selectedFile = fileChooser.showOpenDialog(rootPane.getScene().getWindow());

        if (selectedFile != null) {
            try {
                addToEditorLog("Попытка открыть файл внешним приложением: " + selectedFile.getAbsolutePath());
                Desktop.getDesktop().open(selectedFile);
                addToEditorLog("✓ Файл успешно открыт внешним приложением.");
            } catch (IOException e) {
                addToEditorLog("Ошибка при открытии файла внешним приложением: " + e.getMessage());
                showErrorAlert("Ошибка при открытии файла (Редактор): " + e.getMessage());
            } catch (UnsupportedOperationException e) {
                addToEditorLog("Ошибка: Открытие файлов внешним приложением не поддерживается вашей системой.");
                showErrorAlert("Ошибка: Открытие файлов внешним приложением не поддерживается.");
            }
        } else {
            addToEditorLog("Выбор файла отменен.");
        }
    }

    @FXML
    private void handleEditorShowAllMarkers() {
        try {
            renderEditorMarkersForCurrentPdf();
            addToEditorLog("✓ Все точки загружены из JSON для " + editorCurrentPdfFileName);
        } catch (Exception ex) {
            addToEditorLog("Ошибка загрузки точек: " + ex.getMessage());
        }
    }

    @FXML
    private void handleEditorSaveChanges() {
        try {
            if (editorCurrentPdfFileName == null || editorCurrentPdfFileName.isEmpty()) {
                showErrorAlert("Нет открытого PDF для сохранения.");
                return;
            }
            Path jsonPath = resolveArmatureJsonPath();
            Files.createDirectories(jsonPath.getParent());
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Map<String, ArmatureCoords>> all = Files.exists(jsonPath)
                    ? mapper.readValue(jsonPath.toFile(), new TypeReference<Map<String, Map<String, ArmatureCoords>>>() {})
                    : new java.util.LinkedHashMap<>();

            Map<String, ArmatureCoords> perPdf = all.get(editorCurrentPdfFileName);
            if (perPdf == null) perPdf = new java.util.LinkedHashMap<>();

            // Сначала удалим помеченные на удаление и очистим Excel/таблицу для них
            java.util.Set<String> deletedNow = new java.util.HashSet<>(editorDeletedMarkerIds);
            if (!deletedNow.isEmpty()) {
                for (String delId : deletedNow) {
                    perPdf.remove(delId);
                }
                // Очистка Excel ссылок и UI, если запись реально отсутствует в JSON для этой схемы
                try {
                    String excelPath = DATA_BASE_DIRECTORY + java.io.File.separator + DATABASE_SUBFOLDER + java.io.File.separator + ARMATURE_EXCEL_FILE_NAME;
                    ArmatureExcelService svc = new ArmatureExcelService(excelPath);
                    boolean anyCleared = false;
                    for (String delId : deletedNow) {
                        if (!perPdf.containsKey(delId)) {
                            boolean cleared = svc.clearPdfLink("Арматура", delId);
                            if (cleared) anyCleared = true;
                            if (armatureTable != null && armatureTable.getItems() != null) {
                                for (RowDataDynamic row : armatureTable.getItems()) {
                                    String name = row.getProperty("Арматура").get();
                                    if (name != null && name.trim().equals(delId)) {
                                        String link = row.getProperty("PDF_Схема_и_ID_арматуры").get();
                                        if (link != null && !link.trim().isEmpty()) {
                                            row.getProperty("PDF_Схема_и_ID_арматуры").set("");
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if (anyCleared && armatureTable != null) armatureTable.refresh();
                } catch (Exception ex) {
                    addToEditorLog("Ошибка очистки Excel при удалении: " + ex.getMessage());
                }
                editorDeletedMarkerIds.clear();
            }

            // JSObject массив неудобно читать напрямую; вместо этого прочитаем по одному через JSON.stringify
            Object jsonArr = editorWebView.getEngine().executeScript(String.join("\n",
                    "(function(){",
                    " try {",
                    "  var root = document.getElementById('viewerContainer') || document.getElementById('pdfContainer');",
                    "  if (!root) return '[]';",
                    "  var markers = root.querySelectorAll('[data-mid]');",
                    "  var arr = [];",
                    "  markers.forEach(function(m){",
                    "    var id = m.getAttribute('data-mid');",
                    "    var color = m.getAttribute('data-color') || '#FF0000';",
                    "    var baseSize = parseFloat(m.getAttribute('data-size')||'16');",
                    "    var page = parseInt(m.getAttribute('data-page')||'1',10);",
                    "    var xPdf = parseFloat(m.getAttribute('data-x')||'0');",
                    "    var yPdf = parseFloat(m.getAttribute('data-y')||'0');",
                    "    arr.push({id:id, page:page, x:xPdf, y:yPdf, size:baseSize, color: color});",
                    "  });",
                    "  return JSON.stringify(arr);",
                    " } catch(e){ return '[]'; }",
                    "})();"
            ));

            if (jsonArr instanceof String) {
                String json = (String) jsonArr;
                ArmatureMarkerDTO[] markers = mapper.readValue(json, ArmatureMarkerDTO[].class);
                int added = 0, skipped = 0;
                StringBuilder duplicatesMsg = new StringBuilder();
                for (ArmatureMarkerDTO m : markers) {
                    if (m.id == null || m.id.trim().isEmpty()) continue; // пропускаем пустые
                    ArmatureCoords existing = perPdf.get(m.id);
                    if (existing != null &&
                            Math.abs(existing.getX() - m.x) < 0.01 &&
                            Math.abs(existing.getY() - m.y) < 0.01 &&
                            existing.getPage() == m.page &&
                            Math.round(existing.getWidth()) == Math.round(m.size) &&
                            extractColorFromMarkerType(existing.getMarker_type()).equalsIgnoreCase(m.color)) {
                        skipped++;
                        if (duplicatesMsg.length() > 0) duplicatesMsg.append("\n");
                        duplicatesMsg.append("такая точка: ").append(m.id).append(" , уже создана");
                        continue; // дубликат
                    }
                    ArmatureCoords c = (existing != null) ? existing : new ArmatureCoords();
                    c.setPage(m.page);
                    c.setX(m.x);
                    c.setY(m.y);
                    c.setWidth(m.size);
                    c.setHeight(m.size);
                    if (c.getZoom() <= 0) c.setZoom(1.0);
                    c.setLabel(m.id);
                    c.setMarker_type("square:" + (m.color != null ? m.color : "#FF0000") + ":" + Math.round(m.size));
                    perPdf.put(m.id, c);
                    added++;
                }
                all.put(editorCurrentPdfFileName, perPdf);
                mapper.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), all);
                addToEditorLog(String.format("✓ Сохранение: добавлено %d, пропущено (дубликаты) %d", added, skipped));
                // После успешного сохранения – сверка с таблицей Арматура и запись ссылки в Excel
                try {
                    if (added > 0 && armatureTable != null && editorCurrentPdfFileName != null) {
                        // Берём имена добавленных маркеров и проверяем их наличие в таблице
                        java.util.Set<String> addedIds = new java.util.HashSet<>();
                        for (ArmatureMarkerDTO m : markers) {
                            if (m.id != null && !m.id.trim().isEmpty()) addedIds.add(m.id.trim());
                        }
                        boolean anyUpdated = false;
                        for (RowDataDynamic row : armatureTable.getItems()) {
                            String name = row.getProperty("Арматура").get();
                            if (name != null && addedIds.contains(name.trim())) {
                                // если в JSON уже было – считаем, что ссылка есть; иначе ставим ссылку (имя файла схемы)
                                boolean hadJson = perPdf.containsKey(name.trim());
                                String currentLink = row.getProperty("PDF_Схема_и_ID_арматуры").get();
                                if (hadJson && (currentLink == null || currentLink.trim().isEmpty())) {
                                    boolean ok = new org.example.pult.util.ArmatureExcelService(
                                            DATA_BASE_DIRECTORY + java.io.File.separator + DATABASE_SUBFOLDER + java.io.File.separator + ARMATURE_EXCEL_FILE_NAME
                                    ).updatePdfLink("Арматура", name.trim(), editorCurrentPdfFileName);
                                    if (ok) {
                                        row.getProperty("PDF_Схема_и_ID_арматуры").set(editorCurrentPdfFileName);
                                        anyUpdated = true;
                                    }
                                }
                            }
                        }
                        if (anyUpdated) {
                            armatureTable.refresh();
                            addToEditorLog("✓ Ссылка на схему записана в Excel для новых арматур.");
                        }
                    }
                } catch (Exception ex) {
                    addToEditorLog("Ошибка обновления Excel после сохранения: " + ex.getMessage());
                }
                if (skipped > 0) {
                    final String msg = duplicatesMsg.toString();
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Сохранение");
                        alert.setHeaderText(null);
                        alert.setContentText(msg);
                        alert.showAndWait();
                    });
                }
            } else {
                addToEditorLog("Ошибка: не удалось собрать список меток с экрана");
            }
        } catch (Exception ex) {
            showErrorAlert("Ошибка сохранения: " + ex.getMessage());
        }
    }

    // Вспомогательный DTO для чтения массива маркеров из JSON-строки
    public static class ArmatureMarkerDTO {
        public String id;
        public int page;
        public double x;
        public double y;
        public double size;
        public String color;
    }

    private void addToPdfLog(String message) {
        Platform.runLater(() -> {
            pdfLogArea.appendText(message + "\n");
            pdfLogArea.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void addToEditorLog(String message) {
        Platform.runLater(() -> {
            if (editorLogArea != null) {
                editorLogArea.appendText(message + "\n");
                editorLogArea.setScrollTop(Double.MAX_VALUE);
            }
        });
    }

    private String resolveDataDirectory() {
        Path appRootPath = Paths.get(System.getProperty("user.dir"));
        return appRootPath.resolve("data").toAbsolutePath().toString();
    }

    private Path resolveArmatureJsonPath() {
        // Приоритет: data/armature_coords.json, затем data/Schemes/armature_coords.json
        Path primary = Paths.get(DATA_BASE_DIRECTORY, "armature_coords.json");
        if (Files.exists(primary)) return primary;
        Path fallback = Paths.get(DATA_BASE_DIRECTORY, PDFViewerManager.SCHEMES_SUBFOLDER, "armature_coords.json");
        return Files.exists(fallback) ? fallback : primary; // если нет ни одного — создадим в корне data
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