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

    private String buildRenderNavMarkerScript(ArmatureCoords c) {
        try {
            int size = (int) Math.round(c.getWidth());
            String color = "#FF0000";
            if (c.getMarker_type() != null && c.getMarker_type().startsWith("square:")) {
                String[] parts = c.getMarker_type().split(":");
                if (parts.length >= 2) color = parts[1];
                if (parts.length >= 3) {
                    try { size = Integer.parseInt(parts[2]); } catch (Exception ignored) {}
                }
            }
            return String.format(java.util.Locale.US,
                    String.join("\n",
                            "(function(){ try {",
                            " var root=document.getElementById('viewerContainer')||document.getElementById('pdfContainer'); if(!root) return;",
                            " if(!window.renderNavMarker){",
                            "   window.renderNavMarker=function(id,pageNumber,xPdf,yPdf,sqSize,clr){",
                            "     var canvas=root.querySelector('.pdf-page')||root.querySelector('.canvasWrapper canvas')||root.querySelector('canvas'); if(!canvas) return;",
                            "     var parent=canvas.parentElement; var s=(typeof window.scale==='number')?window.scale:1.0;",
                            "     var sel=\"[data-navmid='\"+id+\"']\"; var mk=parent.querySelector(sel); if(!mk){ mk=document.createElement('div'); mk.setAttribute('data-navmid',id); mk.style.position='absolute'; mk.style.pointerEvents='none'; mk.style.zIndex='9999'; parent.appendChild(mk);} ",
                            "     mk.setAttribute('data-page', String(pageNumber)); mk.setAttribute('data-x', String(xPdf)); mk.setAttribute('data-y', String(yPdf));",
                            "     mk.style.width=sqSize+'px'; mk.style.height=sqSize+'px'; mk.style.border='2px solid '+clr; mk.style.background=clr+'22';",
                            "     var rect=canvas.getBoundingClientRect(); var parentRect=parent.getBoundingClientRect();",
                            "     var left=(rect.left-parentRect.left)+(xPdf*s)-(sqSize/2); var top=(rect.top-parentRect.top)+(yPdf*s)-(sqSize/2);",
                            "     mk.style.left=left+'px'; mk.style.top=top+'px';",
                            "   };",
                            " }",
                            " window.renderNavMarker('%s', %d, %f, %f, %d, '%s');",
                            "} catch(e){} })();"
                    ),
                    escapeJavaScriptString(c.getLabel()), c.getPage(), c.getX(), c.getY(), size, escapeJavaScriptString(color));
        } catch (Exception ignored) {
            return "";
        }
    }

    private String buildSetNavMarkerScript(ArmatureCoords c) {
        try {
            int size = (int) Math.round(c.getWidth());
            String color = "#FF0000";
            if (c.getMarker_type() != null && c.getMarker_type().startsWith("square:")) {
                String[] parts = c.getMarker_type().split(":");
                if (parts.length >= 2) color = parts[1];
                if (parts.length >= 3) {
                    try { size = Integer.parseInt(parts[2]); } catch (Exception ignored) {}
                }
            }
            String comment = c.getComment();
            if (comment == null) comment = "";
            return String.format(java.util.Locale.US,
                    "(function(){ try { if (window.setNavMarker) { window.setNavMarker('%s', %d, %f, %f, %d, '%s', '%s'); } } catch(e){} })();",
                    escapeJavaScriptString(c.getLabel()), c.getPage(), c.getX(), c.getY(), size, escapeJavaScriptString(color), escapeJavaScriptString(comment));
        } catch (Exception ignored) {
            return "";
        }
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
                    // Inject lightweight marker renderer for Schemes tab
                    try {
                        String injectNavJs = String.join("\n",
                                "(function(){ try {",
                                " if (!window.__navMarkerInit){",
                                "   window.__navMarkerInit = true;",
                                "   // Disable default viewer marker to avoid red rectangle",
                                "   try { window.addArmatureMarker = function(){ /* overridden by PDFViewerManager */ }; } catch(e){}",
                                "   window.__navStore = window.__navStore || {};",
                                "   window.clearNavMarkers = function(){",
                                "     try {",
                                "       var root=document.getElementById('viewerContainer')||document.getElementById('pdfContainer'); if(root){",
                                "         root.querySelectorAll('[data-navmid]').forEach(function(n){ try{ n.remove(); }catch(e){} });",
                                "         root.querySelectorAll('[data-navlbl-for]').forEach(function(n){ try{ n.remove(); }catch(e){} });",
                                "         root.querySelectorAll('[data-navcmt-for]').forEach(function(n){ try{ n.remove(); }catch(e){} });",
                                "       }",
                                "       window.__navStore = {};",
                                "     } catch(e){}",
                                "   };",
                                "   window.renderNavMarker = function(id, pageNumber, xPdf, yPdf, size, color){",
                                "     var root=document.getElementById('viewerContainer')||document.getElementById('pdfContainer'); if(!root) return;",
                                "     var canvas=root.querySelector('.pdf-page')||root.querySelector('.canvasWrapper canvas')||root.querySelector('canvas'); if(!canvas) return;",
                                "     var parent=canvas.parentElement; var s=(typeof scale==='number')?scale:1.0;",
                                "     var drawSize = size * s;",
                                "     var sel=\"[data-navmid='\"+id+\"']\"; var mk=parent.querySelector(sel);",
                                "     if(!mk){ mk=document.createElement('div'); mk.setAttribute('data-navmid',id); mk.style.position='absolute'; mk.style.pointerEvents='none'; mk.style.zIndex='9999'; parent.appendChild(mk);} ",
                                "     mk.setAttribute('data-page', String(pageNumber)); mk.setAttribute('data-x', String(xPdf)); mk.setAttribute('data-y', String(yPdf)); mk.setAttribute('data-size', String(size));",
                                "     mk.style.width=drawSize+'px'; mk.style.height=drawSize+'px'; mk.style.border='2px solid '+color; mk.style.background=color+'22';",
                                "     var rect=canvas.getBoundingClientRect(); var parentRect=parent.getBoundingClientRect();",
                                "     var left=(rect.left-parentRect.left)+(xPdf*s)-(drawSize/2); var top=(rect.top-parentRect.top)+(yPdf*s)-(drawSize/2);",
                                "     mk.style.left=left+'px'; mk.style.top=top+'px';",
                                "     // label for nav marker",
                                "     var lblSel = \"[data-navlbl-for='\"+id+\"']\";",
                                "     var lbl = parent.querySelector(lblSel);",
                                "     if (!lbl){ lbl=document.createElement('div'); lbl.setAttribute('data-navlbl-for', id); lbl.style.position='absolute'; lbl.style.pointerEvents='none'; lbl.style.zIndex='10000'; lbl.style.background='transparent'; lbl.style.font='12px sans-serif'; lbl.style.textShadow='0 0 2px #FFFFFF, 0 0 2px #FFFFFF'; lbl.style.textAlign='center'; parent.appendChild(lbl);} ",
                                "     lbl.textContent = id; lbl.style.color = color;",
                                "     var labelLeft = left + (drawSize/2); var labelTop = top - 16;",
                                "     lbl.style.left = labelLeft + 'px'; lbl.style.top = labelTop + 'px'; lbl.style.transform = 'translateX(-50%)';",
                                "     // comment under nav marker (if present in store)",
                                "     try {",
                                "       var store = window.__navStore || {}; var d = store[id];",
                                "       var cSel = \"[data-navcmt-for='\"+id+\"']\";",
                                "       var c = parent.querySelector(cSel);",
                                "       if (!c){ c=document.createElement('div'); c.setAttribute('data-navcmt-for', id); c.style.position='absolute'; c.style.pointerEvents='none'; c.style.zIndex='10000'; c.style.background='transparent'; c.style.font='12px sans-serif'; c.style.textShadow='0 0 2px #FFFFFF, 0 0 2px #FFFFFF'; c.style.whiteSpace='normal'; c.style.wordBreak='break-word'; c.style.overflowWrap='anywhere'; c.style.width='5cm'; c.style.textAlign='center'; parent.appendChild(c);} ",
                                "       var txt = (d && d.comment) ? d.comment : '';",
                                "       c.textContent = txt; c.style.color = color;",
                                "       var centerX = left + (drawSize/2); var belowTop = top + drawSize + 4;",
                                "       c.style.display = (txt && txt!=='') ? 'block' : 'none';",
                                "       // Smart placement near edges (1.5cm) with 2mm gap",
                                "       var THRESH = 56.7; // ~1.5cm",
                                "       var GAP = 7.56;   // ~2mm",
                                "       var cw = c.offsetWidth || parseFloat(getComputedStyle(c).width)||200; var ch = c.offsetHeight || parseFloat(getComputedStyle(c).height)||20;",
                                "       var half = cw/2;",
                                "       var dLeft = left;",
                                "       var dRight = parentRect.width - (left + drawSize);",
                                "       var dTop = top;",
                                "       var dBottom = parentRect.height - (top + drawSize);",
                                "       var side = 'bottom';",
                                "       var minD = Infinity; var minEdge = null;",
                                "       [['left',dLeft],['right',dRight],['top',dTop],['bottom',dBottom]].forEach(function(e){ if (e[1] < THRESH && e[1] < minD) { minD=e[1]; minEdge=e[0]; } });",
                                "       if (minEdge){ if (minEdge==='left') side='right'; else if (minEdge==='right') side='left'; else if (minEdge==='top') side='bottom'; else if (minEdge==='bottom') side='top'; }",
                                "       var cx = centerX; var cy = belowTop;",
                                "       if (side==='top'){ cx=centerX; cy=top - GAP - ch; }",
                                "       else if (side==='right'){ cx=(left+drawSize)+GAP+half; cy=top + (drawSize/2) - (ch/2); }",
                                "       else if (side==='left'){ cx=left - GAP - half; cy=top + (drawSize/2) - (ch/2); }",
                                "       else { cx=centerX; cy=top + drawSize + GAP; }",
                                "       var minX = half + 2; var maxX = parentRect.width - half - 2;",
                                "       var minY = 2; var maxY = parentRect.height - ch - 2;",
                                "       cx = Math.max(minX, Math.min(maxX, cx));",
                                "       cy = Math.max(minY, Math.min(maxY, cy));",
                                "       c.style.left = cx + 'px'; c.style.top = cy + 'px'; c.style.transform = 'translateX(-50%)';",
                                "       c.style.display = (txt && txt!=='') ? 'block' : 'none';",
                                "     } catch(e){}",
                                "   };",
                                "   window.setNavMarker = function(id, pageNumber, xPdf, yPdf, size, color, comment){",
                                "     window.__navStore[id] = {id:id, page:pageNumber, x:xPdf, y:yPdf, size:size, color:color, comment: (comment||'')};",
                                "     var tries = 0;",
                                "     (function drawLater(){",
                                "       tries++;",
                                "       try { if (window.renderNavMarker) { window.renderNavMarker(id, pageNumber, xPdf, yPdf, size, color); } } catch(e){}",
                                "       var root=document.getElementById('viewerContainer')||document.getElementById('pdfContainer');",
                                "       var canvas=root && (root.querySelector('.pdf-page')||root.querySelector('.canvasWrapper canvas')||root.querySelector('canvas'));",
                                "       if (!canvas && tries < 40) { setTimeout(drawLater, 100); } else { if (window.repositionNavMarkers) window.repositionNavMarkers(); }",
                                "     })();",
                                "   };",
                                "   window.repositionNavMarkers=function(){",
                                "     var root=document.getElementById('viewerContainer')||document.getElementById('pdfContainer'); if(!root) return;",
                                "     var canvas=root.querySelector('.pdf-page')||root.querySelector('.canvasWrapper canvas')||root.querySelector('canvas'); if(!canvas) return;",
                                "     var parent=canvas.parentElement; var s=(typeof scale==='number')?scale:1.0; var rect=canvas.getBoundingClientRect(); var parentRect=parent.getBoundingClientRect(); var curPage=(typeof currentPage==='number')?currentPage:1;",
                                "     var store = window.__navStore || {};",
                                "     Object.keys(store).forEach(function(k){ var d=store[k]; if (!d) return; if (d.page!==curPage) return; try { window.renderNavMarker(d.id, d.page, d.x, d.y, d.size, d.color); } catch(e){} });",
                                "   };",
                                "   // Re-render markers when canvas/page is recreated (custom viewer)",
                                "   try {",
                                "     if (!window.__navDomObserver){",
                                "       var target = document.getElementById('pdfContainer') || document.getElementById('viewerContainer');",
                                "       if (target) {",
                                "         window.__navDomObserver = new MutationObserver(function(){",
                                "           try { if (window.repositionNavMarkers) requestAnimationFrame(function(){ window.repositionNavMarkers(); }); } catch(e){}",
                                "         });",
                                "         window.__navDomObserver.observe(target, { childList:true, subtree:true });",
                                "       }",
                                "     }",
                                "   } catch(e){}",
                                "   // Hook toolbar buttons to reposition after render",
                                "   try { ['zoom_in','zoom_out','prev','next','go_to'].forEach(function(id){ var el=document.getElementById(id); if (el && !el.__navHook){ el.addEventListener('click', function(){ setTimeout(function(){ if(window.repositionNavMarkers) window.repositionNavMarkers(); }, 0); }); el.__navHook=true; } }); } catch(e){}",
                                "   // Reposition on window resize too",
                                "   try { window.addEventListener('resize', function(){ if(window.repositionNavMarkers) window.repositionNavMarkers(); }, {passive:true}); } catch(e){}",
                                "   try{ if (window.PDFViewerApplication && PDFViewerApplication.eventBus){ var eb=PDFViewerApplication.eventBus; eb.on('pagerendered', function(){ if(window.repositionNavMarkers) window.repositionNavMarkers(); }); eb.on('scalechanged', function(){ if(window.repositionNavMarkers) window.repositionNavMarkers(); }); } }catch(e){}",
                                " }",
                                "} catch(e){} })();"
                        );
                        engine.executeScript(injectNavJs);
                    } catch (Exception ignored) {}

                    if (pendingPdfBase64 != null) {
                        String base64ToLoad = pendingPdfBase64;
                        ArmatureCoords coordsToCenter = pendingArmatureCoords;
                        pendingPdfBase64 = null;
                        pendingArmatureCoords = null;

                        Platform.runLater(() -> {
                            try {
                                // Добавлена проверка на наличие engine перед вызовом executeScript
                                if (engine != null) {
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
                                        // Сохраним и отрисуем маркер (ждём, пока появится canvas)
                                        engine.executeScript(buildSetNavMarkerScript(coordsToCenter));
                                    } else {
                                        logDebug("Вызов loadPdfFromBase64()");
                                        engine.executeScript(String.format("loadPdfFromBase64('%s');",
                                                escapeJavaScriptString(base64ToLoad)));
                                    }
                                } else {
                                    notifyLoadingError("WebEngine не инициализирован.");
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

                    // Добавлена проверка на наличие engine перед вызовом getLocation
                    boolean alreadyLoaded = engine != null && engine.getLocation() != null && engine.getLocation().contains("viewer.html") && !isViewerHtmlLoading;
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
                                // Добавлена проверка на наличие engine перед вызовом executeScript
                                if (engine != null) {
                                    engine.executeScript(jsCall);
                                    engine.executeScript("if (window.clearNavMarkers) window.clearNavMarkers();");
                                    engine.executeScript(buildSetNavMarkerScript(pendingArmatureCoords));
                                } else {
                                    notifyLoadingError("WebEngine не инициализирован.");
                                }
                            } else {
                                if (engine != null) {
                                    engine.executeScript(String.format("loadPdfFromBase64('%s');",
                                            escapeJavaScriptString(base64)));
                                } else {
                                    notifyLoadingError("WebEngine не инициализирован.");
                                }
                            }
                        } catch (Exception e) {
                            notifyLoadingError("Ошибка при повторной загрузке PDF: " + e.getMessage());
                        }
                    } else {
                        isViewerHtmlLoading = true;
                        if (engine != null) {
                            engine.load(viewerHtml);
                        } else {
                            notifyLoadingError("WebEngine не инициализирован.");
                        }
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

                    // Добавлена проверка на наличие engine перед вызовом getLocation
                    boolean alreadyLoaded = engine != null && engine.getLocation() != null && engine.getLocation().contains("viewer.html") && !isViewerHtmlLoading;
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
                                // Добавлена проверка на наличие engine перед вызовом executeScript
                                if (engine != null) {
                                    engine.executeScript(jsCall);
                                    engine.executeScript("if (window.clearNavMarkers) window.clearNavMarkers();");
                                    engine.executeScript(buildSetNavMarkerScript(coords));
                                } else {
                                    notifyLoadingError("WebEngine не инициализирован.");
                                }
                            } else {
                                if (engine != null) {
                                    engine.executeScript(String.format("loadPdfFromBase64('%s');",
                                            escapeJavaScriptString(base64)));
                                } else {
                                    notifyLoadingError("WebEngine не инициализирован.");
                                }
                            }
                        } catch (Exception e) {
                            notifyLoadingError("Ошибка при повторной загрузке PDF: " + e.getMessage());
                        }
                    } else {
                        isViewerHtmlLoading = true;
                        if (engine != null) {
                            engine.load(viewerHtml);
                        } else {
                            notifyLoadingError("WebEngine не инициализирован.");
                        }
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
                if (engine != null) {
                    engine.executeScript("currentPageNumber = " + pageNumber + "; queueRenderPage(currentPageNumber);");
                } else {
                    System.err.println("WebEngine не инициализирован при попытке перехода к странице.");
                }
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