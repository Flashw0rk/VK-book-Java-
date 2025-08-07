package org.example.pult;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.example.pult.model.ArmatureCoords;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Locale; // <<< ДОБАВЛЕН ИМПОРТ
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class PDFViewerManager {
    private static final boolean DEBUG_MODE = true;

    public interface PDFLoadingListener {
        void onLoadingStart();
        void onLoadingComplete();
        void onLoadingError(String error);
    }

    public static final String SCHEMES_SUBFOLDER = "Schemes";

    private WebEngine engine;
    private String dataDirectory;
    private PDFLoadingListener loadingListener;

    private String currentLoadingPdfUrl = null;
    private String lastLoadedPdfUrl = null;
    private String pendingPdfBase64 = null;
    private String pendingArmatureId = null;
    private ArmatureCoords pendingArmatureCoords = null;
    private volatile boolean isViewerHtmlLoading = false;
    private CompletableFuture<Void> loadingTimeoutFuture;

    public void setLoadingListener(PDFLoadingListener listener) {
        this.loadingListener = listener;
    }

    public void initialize(WebView pdfWebView, String dataDir) {
        this.dataDirectory = dataDir;
        this.engine = pdfWebView.getEngine();
        engine.setJavaScriptEnabled(true);

        engine.getLoadWorker().exceptionProperty().addListener((obs, oldEx, newEx) -> {
            if (newEx != null && engine.getLoadWorker().getState() == Worker.State.FAILED) {
                logDebug("WebEngine exception: " + newEx.getMessage());
                notifyLoadingError("Ошибка загрузки WebEngine: " + newEx.getMessage());
                newEx.printStackTrace();
                resetLoadingState();
            }
        });

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            logDebug("DEBUG: WebEngine состояние изменилось с " + oldState + " на " + newState + " для " + engine.getLocation());

            if (newState == Worker.State.SUCCEEDED && engine.getLocation().contains("viewer.html")) {
                try {
                    JSObject window = (JSObject) engine.executeScript("window");
                    window.setMember("javaApp", new JavaApp());

                    if (pendingPdfBase64 != null) {
                        String base64ToLoad = pendingPdfBase64;
                        ArmatureCoords coordsToCenter = pendingArmatureCoords;
                        pendingPdfBase64 = null;
                        pendingArmatureCoords = null;

                        Platform.runLater(() -> {
                            try {
                                if (coordsToCenter != null) {
                                    // <<< ИСПРАВЛЕНО ЗДЕСЬ: Добавлена Locale.US
                                    String jsCall = String.format(Locale.US,
                                            "loadAndCenterPdf('%s', %f, %f, %f, %d, '%s');",
                                            escapeJavaScriptString(base64ToLoad),
                                            coordsToCenter.getX(),
                                            coordsToCenter.getY(),
                                            coordsToCenter.getZoom(),
                                            coordsToCenter.getPage(),
                                            escapeJavaScriptString(coordsToCenter.getLabel())
                                    );

                                    logDebug("JS Call to loadAndCenterPdf for PDF: " + currentLoadingPdfUrl);
                                    engine.executeScript(jsCall);
                                } else {
                                    logDebug("Вызов loadPdfFromBase64()");
                                    engine.executeScript(String.format("loadPdfFromBase64('%s');",
                                            escapeJavaScriptString(base64ToLoad)));
                                }
                            } catch (Exception jsEx) {
                                notifyLoadingError("Ошибка JS при загрузке PDF: " + jsEx.getMessage());
                                cancelLoadingTimeout();
                            }
                        });
                    }

                    lastLoadedPdfUrl = currentLoadingPdfUrl;
                    currentLoadingPdfUrl = null;
                    isViewerHtmlLoading = false;
                } catch (Exception e) {
                    notifyLoadingError("Ошибка инициализации JavaScript: " + e.getMessage());
                    resetLoadingState();
                }
            } else if (newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                String msg = (engine.getLoadWorker().getException() != null)
                        ? engine.getLoadWorker().getException().getMessage()
                        : "Неизвестная ошибка загрузки WebEngine";
                notifyLoadingError("Ошибка загрузки WebEngine: " + msg);
                resetLoadingState();
            }
        });
    }

    public void loadPdf(String pdfPath, String armatureId) {
        notifyLoadingStart();
        cancelLoadingTimeout();

        loadingTimeoutFuture = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(5000);
                Platform.runLater(this::notifyLoadingComplete);
            } catch (InterruptedException ignored) {}
        });

        CompletableFuture.runAsync(() -> {
            try {
                Path filePath = Paths.get(pdfPath).toAbsolutePath();
                if (!Files.exists(filePath)) {
                    notifyLoadingError("Файл не найден: " + filePath);
                    cancelLoadingTimeout();
                    return;
                }

                byte[] bytes = Files.readAllBytes(filePath);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String viewerHtml = getViewerHtmlUrl();
                if (viewerHtml == null) return;

                Platform.runLater(() -> {
                    currentLoadingPdfUrl = pdfPath;

                    pendingPdfBase64 = base64;
                    pendingArmatureId = armatureId;
                    pendingArmatureCoords = getCoordsIfAvailable(pdfPath, armatureId);

                    boolean alreadyLoaded = engine.getLocation() != null && engine.getLocation().contains("viewer.html") && !isViewerHtmlLoading;
                    if (alreadyLoaded) {
                        try {
                            if (pendingArmatureCoords != null) {
                                // <<< ИСПРАВЛЕНО ЗДЕСЬ: Добавлена Locale.US
                                String jsCall = String.format(Locale.US,
                                        "loadAndCenterPdf('%s', %f, %f, %f, %d, '%s');",
                                        escapeJavaScriptString(base64),
                                        pendingArmatureCoords.getX(),
                                        pendingArmatureCoords.getY(),
                                        pendingArmatureCoords.getZoom(),
                                        pendingArmatureCoords.getPage(),
                                        escapeJavaScriptString(pendingArmatureCoords.getLabel())
                                );
                                engine.executeScript(jsCall);
                            } else {
                                engine.executeScript(String.format("loadPdfFromBase64('%s');",
                                        escapeJavaScriptString(base64)));
                            }
                        } catch (Exception e) {
                            notifyLoadingError("Ошибка при повторной загрузке PDF: " + e.getMessage());
                        }
                    } else {
                        isViewerHtmlLoading = true;
                        engine.load(viewerHtml);
                    }
                });

            } catch (Exception e) {
                notifyLoadingError("Ошибка при загрузке PDF: " + e.getMessage());
                cancelLoadingTimeout();
            }
        });
    }

    public void loadPdfCentered(String pdfPath, ArmatureCoords coords) {
        notifyLoadingStart();
        cancelLoadingTimeout();

        loadingTimeoutFuture = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(5000);
                Platform.runLater(this::notifyLoadingComplete);
            } catch (InterruptedException ignored) {}
        });

        CompletableFuture.runAsync(() -> {
            try {
                Path filePath = Paths.get(pdfPath).toAbsolutePath();
                if (!Files.exists(filePath)) {
                    notifyLoadingError("Файл не найден: " + filePath);
                    cancelLoadingTimeout();
                    return;
                }

                byte[] bytes = Files.readAllBytes(filePath);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                String viewerHtml = getViewerHtmlUrl();
                if (viewerHtml == null) return;

                Platform.runLater(() -> {
                    currentLoadingPdfUrl = pdfPath;
                    pendingPdfBase64 = base64;
                    pendingArmatureCoords = coords;
                    pendingArmatureId = null;

                    boolean alreadyLoaded = engine.getLocation() != null && engine.getLocation().contains("viewer.html") && !isViewerHtmlLoading;
                    if (alreadyLoaded) {
                        try {
                            if (coords != null) {
                                // <<< ИСПРАВЛЕНО ЗДЕСЬ: Добавлена Locale.US
                                String jsCall = String.format(Locale.US,
                                        "loadAndCenterPdf('%s', %f, %f, %f, %d, '%s');",
                                        escapeJavaScriptString(base64),
                                        coords.getX(),
                                        coords.getY(),
                                        coords.getZoom(),
                                        coords.getPage(),
                                        escapeJavaScriptString(coords.getLabel())
                                );
                                engine.executeScript(jsCall);
                            } else {
                                engine.executeScript(String.format("loadPdfFromBase64('%s');",
                                        escapeJavaScriptString(base64)));
                            }
                        } catch (Exception e) {
                            notifyLoadingError("Ошибка при повторной загрузке PDF: " + e.getMessage());
                        }
                    } else {
                        isViewerHtmlLoading = true;
                        engine.load(viewerHtml);
                    }
                });

            } catch (Exception e) {
                notifyLoadingError("Ошибка при загрузке PDF: " + e.getMessage());
                cancelLoadingTimeout();
            }
        });
    }


    private String getViewerHtmlUrl() {
        URL viewerUrl = getClass().getResource("/pdfjs/web/viewer.html");
        if (viewerUrl == null) {
            notifyLoadingError("Не найден viewer.html в ресурсах.");
            cancelLoadingTimeout();
            return null;
        }
        return viewerUrl.toExternalForm();
    }

    private void cancelLoadingTimeout() {
        if (loadingTimeoutFuture != null && !loadingTimeoutFuture.isDone()) {
            loadingTimeoutFuture.cancel(true);
        }
    }

    private void resetLoadingState() {
        currentLoadingPdfUrl = null;
        pendingPdfBase64 = null;
        pendingArmatureId = null;
        pendingArmatureCoords = null;
        isViewerHtmlLoading = false;
        cancelLoadingTimeout();
    }

    private String escapeJavaScriptString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private void notifyLoadingStart() {
        logDebug("DEBUG: onLoadingStart() вызван.");
        if (loadingListener != null) {
            Platform.runLater(loadingListener::onLoadingStart);
        }
    }

    private void notifyLoadingComplete() {
        logDebug("DEBUG: onLoadingComplete() вызван.");
        if (loadingListener != null) {
            Platform.runLater(loadingListener::onLoadingComplete);
        }
    }

    private void notifyLoadingError(String error) {
        logDebug("DEBUG: onLoadingError() вызван.\n" + error);
        if (loadingListener != null) {
            Platform.runLater(() -> loadingListener.onLoadingError(error));
        }
    }

    private static void logDebug(String message) {
        if (DEBUG_MODE) {
            System.out.println(message);
        }
    }

    public class JavaApp {
        public void log(String message) {
            System.out.println("JS: " + message);
        }

        public void error(String message) {
            System.err.println("JS ERROR: " + message);
        }

        public void warn(String message) {
            System.out.println("JS WARN: " + message);
        }

        public void pdfLoaded() {
            System.out.println("JS → Java: pdfLoaded()");
            cancelLoadingTimeout();
            Platform.runLater(() -> {
                notifyLoadingComplete();
            });
        }

        public void scrollToArmature(String armatureId) {
            System.out.println("JavaApp: scrollToArmature(" + armatureId + ")");
        }
    }

    public void navigateToPage(int pageNumber) {
        Platform.runLater(() -> {
            try {
                engine.executeScript("currentPageNumber = " + pageNumber + "; queueRenderPage(currentPageNumber);");
            } catch (Exception e) {
                System.err.println("Ошибка перехода к странице: " + e.getMessage());
            }
        });
    }

    private ArmatureCoords getCoordsIfAvailable(String pdfPath, String armatureId) {
        if (armatureId == null || armatureId.trim().isEmpty()) {
            return null;
        }
        try {
            Map<String, Map<String, ArmatureCoords>> allCoords =
                    readAllArmatureCoordinatesFromFile(dataDirectory + File.separator + "armature_coords.json");
            String fileName = new File(pdfPath).getName();
            if (allCoords != null && allCoords.containsKey(fileName)) {
                Map<String, ArmatureCoords> map = allCoords.get(fileName);
                return map.get(armatureId);
            }
        } catch (IOException e) {
            System.err.println("Ошибка чтения JSON координат: " + e.getMessage());
        }
        return null;
    }

    public Map<String, Map<String, ArmatureCoords>> readAllArmatureCoordinatesFromFile(String jsonFilePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        File jsonFile = new File(jsonFilePath);
        if (jsonFile.exists()) {
            return mapper.readValue(jsonFile, new TypeReference<Map<String, Map<String, ArmatureCoords>>>() {});
        }
        return null;
    }
}