package org.example.pult;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.example.pult.model.ArmatureCoords;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class PDFViewerManager {

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
            if (newEx != null) {
                notifyLoadingError("Ошибка загрузки WebEngine: " + newEx.getMessage());
                newEx.printStackTrace();
                resetLoadingState();
            }
        });

        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            System.out.println("DEBUG: WebEngine состояние изменилось с " + oldState + " на " + newState + " для " + engine.getLocation());

            if (engine.getLocation() != null && engine.getLocation().contains("viewer.html")) {
                if (newState == Worker.State.SUCCEEDED || newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                    isViewerHtmlLoading = false;
                }
            }

            if (newState == Worker.State.SUCCEEDED) {
                if (engine.getLocation() != null && engine.getLocation().contains("viewer.html")) {
                    try {
                        JSObject window = (JSObject) engine.executeScript("window");
                        window.setMember("javaApp", new JavaApp());

                        if (pendingPdfBase64 != null) {
                            String base64ToLoad = pendingPdfBase64;
                            pendingPdfBase64 = null;
                            pendingArmatureId = null;

                            Platform.runLater(() -> {
                                try {
                                    System.out.println("DEBUG: Внедрение PDF через loadPdfFromBase64()");
                                    engine.executeScript("loadPdfFromBase64('" + escapeJavaScriptString(base64ToLoad) + "');");
                                } catch (Exception jsEx) {
                                    notifyLoadingError("Ошибка JS при загрузке PDF: " + jsEx.getMessage());
                                    cancelLoadingTimeout();
                                }
                            });
                        }

                        lastLoadedPdfUrl = currentLoadingPdfUrl;
                        currentLoadingPdfUrl = null;
                    } catch (Exception e) {
                        notifyLoadingError("Ошибка инициализации JavaScript: " + e.getMessage());
                        resetLoadingState();
                    }
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

                currentLoadingPdfUrl = pdfPath;
                pendingPdfBase64 = base64;
                pendingArmatureId = armatureId;

                Platform.runLater(() -> {
                    if (isViewerHtmlLoading) return;
                    isViewerHtmlLoading = true;
                    engine.load(viewerHtml);
                });

            } catch (Exception e) {
                notifyLoadingError("Ошибка при загрузке PDF: " + e.getMessage());
                resetLoadingState();
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
                    isViewerHtmlLoading = true;

                    engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
                        if (newState == Worker.State.SUCCEEDED) {
                            try {
                                JSObject window = (JSObject) engine.executeScript("window");
                                window.setMember("javaApp", new JavaApp());

                                String jsCall = String.format(
                                        "loadAndCenterPdf('%s', %f, %f, %f, %d);",
                                        escapeJavaScriptString(base64),
                                        coords.getX(),
                                        coords.getY(),
                                        coords.getZoom(),
                                        coords.getPage()
                                );

                                System.out.println("DEBUG: JS вызов -> " + jsCall);
                                engine.executeScript(jsCall);
                            } catch (Exception e) {
                                notifyLoadingError("Ошибка JS: " + e.getMessage());
                                cancelLoadingTimeout();
                            }
                        }
                    });

                    engine.load(viewerHtml);
                });

            } catch (Exception e) {
                notifyLoadingError("Ошибка при загрузке и центрировании: " + e.getMessage());
                cancelLoadingTimeout();
            }
        });
    }

    private String getViewerHtmlUrl() {
        URL viewerUrl = getClass().getResource("/pdfjs/web/viewer.html");
        if (viewerUrl == null) {
            notifyLoadingError("Не найден viewer.html");
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
        if (loadingListener != null) {
            Platform.runLater(loadingListener::onLoadingStart);
        }
    }

    private void notifyLoadingComplete() {
        if (loadingListener != null) {
            Platform.runLater(loadingListener::onLoadingComplete);
        }
    }

    private void notifyLoadingError(String error) {
        if (loadingListener != null) {
            Platform.runLater(() -> loadingListener.onLoadingError(error));
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
            Platform.runLater(() -> PDFViewerManager.this.notifyLoadingComplete());
        }
        public void scrollToArmature(String armatureId) {
            System.out.println("JavaApp: scrollToArmature(" + armatureId + ")");
        }
    }

    public void navigateToPage(int pageNumber) {
        Platform.runLater(() -> {
            try {
                engine.executeScript("currentPageNumber = " + pageNumber + "; queueRenderPage(currentPageNumber); updateUI();");
            } catch (Exception e) {
                System.err.println("Ошибка перехода к странице: " + e.getMessage());
            }
        });
    }

    public Map<String, Map<String, ArmatureCoords>> readAllArmatureCoordinatesFromFile(String jsonFilePath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        File jsonFile = new File(jsonFilePath);
        if (jsonFile.exists()) {
            return mapper.readValue(jsonFile,
                    new TypeReference<Map<String, Map<String, ArmatureCoords>>>() {});
        }
        return null;
    }
}
