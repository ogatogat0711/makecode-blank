package app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.Parent;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MakeCodeBlankApp extends Application {

    /** 画像化の倍率（大きいほど印刷がきれい、ファイルは重くなる） */
    static final int SCALE = 4;
    /** PDFの余白（pt） */
    static final float MARGIN = 40f;
    static final double[] ZOOM_STEPS = { 0.25, 0.33, 0.5, 0.67, 0.75, 0.9, 1.0,
            1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0, 4.0 };
    static final double ZOOM_MIN = 0.25;
    static final double ZOOM_MAX = 4.0;
    static final Pattern ERROR_POS = Pattern.compile("\\((\\d+)\\s*,\\s*(\\d+)\\)|:(\\d+):(\\d+)|(?:line|行)\\s*(\\d+)");
    static final Pattern PLACE_CALL = Pattern.compile("(agent|blocks)\\s*\\.\\s*place\\s*\\(");
    static final String TITLE = "MakeCode設計書メーカー";
    static final String VIEWER_NAME = "makecode-viewer.exe";
    static final String VIEWER_DEV = "viewer/target/release/" + VIEWER_NAME;
    static final String VIEWER_PROPERTY = "makecode.viewer";
    static final String PREF_ZOOM = "defaultZoom";
    static final String PREF_LAYOUT = "defaultLayout";
    static final String ZOOM_FIT = "fit";
    static final String[] ZOOM_LABELS = { "ウィンドウに合わせる", "50%", "75%", "100%", "125%", "150%", "200%" };
    static final String[] ZOOM_VALUES = { ZOOM_FIT, "0.5", "0.75", "1.0", "1.25", "1.5", "2.0" };
    static final String[] LAYOUT_LABELS = { "MakeCode の配置のまま", "縦に並べる", "横に並べる" };
    static final String[] LAYOUT_VALUES = { "none", "vertical", "horizontal" };
    static final String PREF_TITLE_Q = "pdfTitleQuestion";
    static final String PREF_TITLE_A = "pdfTitleAnswer";
    static final String PREF_PDF_DIALOG = "pdfShowDialog";
    static final String DEFAULT_TITLE_Q = "問題";
    static final String DEFAULT_TITLE_A = "解答";

    static final String SAMPLE = """
            player.onChat("run", function () {
                for (let index = 0; index < 5; index++) {
                    agent.place(FORWARD)
                    agent.move(RIGHT, 1)
                }

                agent.move(FORWARD, 1)
                agent.move(LEFT, 1)
            })
            """;

    // JSから呼ばれるオブジェクト。GCで消えないようフィールドで保持する
    private final Bridge bridge = new Bridge();
    private final Label status = new Label("MakeCode レンダラーを読み込み中…");
    private final Label zoomLabel = new Label("100%");
    private WebEngine engine;
    private WebView view;
    private WebEngine editorEngine;
    private WebView editorView;
    private WebEngine simEngine;
    private WebView simView;
    private boolean simReady;
    private Button previewBtn;
    private Process previewProcess;
    private TextArea codeArea;
    private SplitPane split;
    private boolean editorReady;
    private Scene scene;
    private Parent homeRoot;
    private Parent editorRoot;
    private Parent ocrRoot;
    private Stage stage;
    private boolean exporting;
    private double zoom = 1.0;
    private final BooleanProperty rendered = new SimpleBooleanProperty(false);
    private final Preferences prefs = Preferences.userNodeForPackage(MakeCodeBlankApp.class);
    private final HandwritingOcr ocr = new HandwritingOcr();
    private BufferedImage ocrImage;
    private ImageView ocrView;
    private StackPane ocrBusy;
    private Label ocrStatus;
    private Button ocrOpenBtn;
    private Button ocrRotateBtn;
    private Button ocrRunBtn;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        editorRoot = buildEditorScreen();
        homeRoot = buildHomeScreen();
        ocrRoot = buildOcrScreen();

        scene = new Scene(homeRoot, 1200, 750);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!e.isControlDown() || scene.getRoot() != editorRoot || !rendered.get()) return;
            KeyCode c = e.getCode();
            if (c == KeyCode.PLUS || c == KeyCode.ADD || c == KeyCode.EQUALS) {
                stepZoom(1);
                e.consume();
            } else if (c == KeyCode.MINUS || c == KeyCode.SUBTRACT) {
                stepZoom(-1);
                e.consume();
            }
        });

        stage.setScene(scene);
        applyDefaultZoom();
        showHome();
        stage.show();
    }

    private String defaultZoom() {
        return prefs.get(PREF_ZOOM, "1.0");
    }

    private String defaultLayout() {
        return prefs.get(PREF_LAYOUT, "none");
    }

    private void applyDefaultZoom() {
        String z = defaultZoom();
        if (ZOOM_FIT.equals(z)) {
            if (rendered.get()) fitZoom();
            return;
        }
        try {
            applyZoom(Double.parseDouble(z));
        } catch (NumberFormatException ignored) {
        }
    }

    private void applyRenderDefaults() {
        String layout = defaultLayout();
        if ("vertical".equals(layout)) js("arrangeVertical(true)");
        else if ("horizontal".equals(layout)) js("arrangeHorizontal(true)");
        if (ZOOM_FIT.equals(defaultZoom())) fitZoom();
    }

    private static int indexOf(String[] values, String v, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return i;
        return fallback;
    }

    private void openSettings() {
        TextField invite = new TextField(ocr.inviteCode());
        invite.setPromptText("招待コード");
        invite.setPrefColumnCount(22);
        ComboBox<String> zoomBox = new ComboBox<>(FXCollections.observableArrayList(ZOOM_LABELS));
        zoomBox.getSelectionModel().select(indexOf(ZOOM_VALUES, defaultZoom(), 3));
        ComboBox<String> layoutBox = new ComboBox<>(FXCollections.observableArrayList(LAYOUT_LABELS));
        layoutBox.getSelectionModel().select(indexOf(LAYOUT_VALUES, defaultLayout(), 0));

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("招待コード（画像認識用）"), invite);
        grid.addRow(1, new Label("既定の表示倍率"), zoomBox);
        grid.addRow(2, new Label("変換後の既定の配置"), layoutBox);

        TextField titleQ = new TextField(prefs.get(PREF_TITLE_Q, DEFAULT_TITLE_Q));
        titleQ.setPromptText("空欄なら見出しなし");
        TextField titleA = new TextField(prefs.get(PREF_TITLE_A, DEFAULT_TITLE_A));
        titleA.setPromptText("空欄なら見出しなし");
        CheckBox showPdfDialog = new CheckBox("PDF出力時に見出しの編集・プレビュー画面を表示する");
        showPdfDialog.setSelected(prefs.getBoolean(PREF_PDF_DIALOG, true));
        grid.add(new Separator(), 0, 3, 2, 1);
        grid.addRow(4, new Label("PDF の見出し（問題）"), titleQ);
        grid.addRow(5, new Label("PDF の見出し（解答）"), titleA);
        grid.add(showPdfDialog, 0, 6, 2, 1);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("設定");
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        ocr.setInviteCode(invite.getText());
        prefs.put(PREF_ZOOM, ZOOM_VALUES[Math.max(0, zoomBox.getSelectionModel().getSelectedIndex())]);
        prefs.put(PREF_LAYOUT, LAYOUT_VALUES[Math.max(0, layoutBox.getSelectionModel().getSelectedIndex())]);
        prefs.put(PREF_TITLE_Q, titleQ.getText().strip());
        prefs.put(PREF_TITLE_A, titleA.getText().strip());
        prefs.putBoolean(PREF_PDF_DIALOG, showPdfDialog.isSelected());
        applyDefaultZoom();
    }

    private void showScreen(Parent root, String subtitle) {
        scene.setRoot(root);
        stage.setTitle(subtitle == null ? TITLE : TITLE + " － " + subtitle);
    }

    private void showHome()   { showScreen(homeRoot, null); }
    private void showEditor() { showScreen(editorRoot, "コード調整・ワークシート化"); }
    private void showOcr()    { showScreen(ocrRoot, "画像認識"); }

    private Parent buildHomeScreen() {
        Label title = new Label(TITLE);
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label lead = new Label("MakeCode のプログラムをブロック図にして、穴埋め教材の PDF を作ります");
        lead.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");

        Button codeBtn = homeChoice("JavaScriptコードから",
                "コードを貼り付けor編集してブロック図に変換します");
        codeBtn.setOnAction(e -> showEditor());
        Button ocrBtn = homeChoice("画像認識から",
                "手書きの設計書の画像から作ります");
        ocrBtn.setOnAction(e -> enterOcr());

        VBox box = new VBox(16, title, lead, new Separator(), codeBtn, ocrBtn);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        box.setMaxWidth(Region.USE_PREF_SIZE);

        Button settingsBtn = Icons.button(Icons.gear(), "設定");
        settingsBtn.setOnAction(e -> openSettings());
        StackPane.setAlignment(settingsBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(settingsBtn, new Insets(12));

        StackPane pane = new StackPane(box, settingsBtn);
        pane.setStyle("-fx-background-color: #fafafa;");
        return pane;
    }

    private static Button homeChoice(String title, String detail) {
        Label t = new Label(title);
        t.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label d = new Label(detail);
        d.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        VBox g = new VBox(4, t, d);
        g.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button();
        b.setGraphic(g);
        b.setPrefSize(440, 74);
        b.setAlignment(Pos.CENTER_LEFT);
        return b;
    }

    private Parent buildOcrScreen() {
        Button backBtn = new Button("← 戻る");
        backBtn.setOnAction(e -> showHome());
        ocrOpenBtn = new Button("画像を開く…");
        ocrOpenBtn.setOnAction(e -> openOcrImage());
        ocrRotateBtn = new Button("右に90°回転");
        ocrRotateBtn.setDisable(true);
        ocrRotateBtn.setOnAction(e -> setOcrImage(HandwritingOcr.rotateRight(ocrImage)));
        ocrRunBtn = new Button("認識する");
        ocrRunBtn.setDisable(true);
        ocrRunBtn.setStyle("-fx-font-weight: bold;");
        ocrRunBtn.setOnAction(e -> runOcr());
        Label server = new Label(HandwritingOcr.hasServer()
                ? "認識サーバー設定済"
                : "認識サーバー未設定");
        server.setStyle("-fx-text-fill: #666;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar bar = new ToolBar(backBtn, new Separator(), ocrOpenBtn, ocrRotateBtn,
                new Separator(), ocrRunBtn, spacer, server);

        Label hint = new Label("手書きの設計書の写真を開いてください");
        hint.setStyle("-fx-font-size: 15px; -fx-text-fill: #666;");
        ocrView = new ImageView();
        ocrView.setPreserveRatio(true);
        ocrView.setSmooth(true);
        hint.visibleProperty().bind(ocrView.imageProperty().isNull());

        ProgressIndicator spin = new ProgressIndicator();
        Label busyText = new Label("認識中…（数十秒かかることがあります）");
        busyText.setStyle("-fx-font-size: 14px; -fx-text-fill: white;");
        VBox busyBox = new VBox(12, spin, busyText);
        busyBox.setAlignment(Pos.CENTER);
        ocrBusy = new StackPane(busyBox);
        ocrBusy.setStyle("-fx-background-color: rgba(0, 0, 0, 0.45);");
        ocrBusy.setVisible(false);

        StackPane center = new StackPane(hint, ocrView, ocrBusy);
        center.setMinSize(0, 0);
        center.setPadding(new Insets(12));
        center.setStyle("-fx-background-color: #f4f4f4;");
        ocrView.fitWidthProperty().bind(center.widthProperty().subtract(24));
        ocrView.fitHeightProperty().bind(center.heightProperty().subtract(24));

        ocrStatus = new Label(HandwritingOcr.hasServer() ? ""
                : "認識サーバーの URL が設定されていません（HandwritingOcr.DEFAULT_SERVER_URL か環境変数 "
                        + HandwritingOcr.SERVER_URL_ENV + "）");
        BorderPane root = new BorderPane(center);
        root.setTop(bar);
        BorderPane.setMargin(ocrStatus, new Insets(4, 8, 4, 8));
        root.setBottom(ocrStatus);
        return root;
    }

    private void openOcrImage() {
        FileChooser fc = new FileChooser();
        fc.setTitle("手書きの設計メモの画像");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("画像", "*.jpg", "*.jpeg", "*.png"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        try {
            setOcrImage(HandwritingOcr.load(f));
            ocrStatus.setText(f.getName() + " を開きました。向きが正しければ「認識する」を押してください");
        } catch (HandwritingOcr.OcrException ex) {
            ocrStatus.setText(ex.getMessage());
        }
    }

    private void setOcrImage(BufferedImage img) {
        ocrImage = img;
        try {
            ocrView.setImage(new Image(new ByteArrayInputStream(HandwritingOcr.toJpeg(img, 2048))));
        } catch (IOException ex) {
            ocrStatus.setText("画像を表示できませんでした: " + ex.getMessage());
        }
        ocrRotateBtn.setDisable(false);
        ocrRunBtn.setDisable(false);
    }

    private void setOcrBusy(boolean busy) {
        ocrBusy.setVisible(busy);
        ocrOpenBtn.setDisable(busy);
        ocrRotateBtn.setDisable(busy || ocrImage == null);
        ocrRunBtn.setDisable(busy || ocrImage == null);
    }

    private boolean askInviteCode(String header) {
        TextInputDialog dialog = new TextInputDialog(ocr.inviteCode());
        dialog.initOwner(stage);
        dialog.setTitle("招待コード");
        dialog.setHeaderText(header);
        dialog.setContentText("招待コード:");
        String code = dialog.showAndWait().map(String::strip).orElse("");
        if (code.isEmpty()) return false;
        ocr.setInviteCode(code);
        return true;
    }

    private void enterOcr() {
        if (ocr.inviteCode().isEmpty()
                && !askInviteCode("画像認識を使うには招待コードが必要です。招待コードを入力してください")) {
            return;
        }
        showOcr();
    }

    private void runOcr() {
        if (ocrImage == null) return;
        if (ocr.inviteCode().isEmpty()) {
            showHome();
            return;
        }
        BufferedImage img = ocrImage;
        setOcrBusy(true);
        ocrStatus.setText("認識中です…");
        Thread worker = new Thread(() -> {
            try {
                HandwritingOcr.Result result = ocr.recognize(img);
                Platform.runLater(() -> onOcrDone(result));
            } catch (HandwritingOcr.OcrException ex) {
                Platform.runLater(() -> onOcrFailed(ex));
            } catch (RuntimeException ex) {
                Platform.runLater(() -> onOcrFailed(new HandwritingOcr.OcrException(String.valueOf(ex))));
            }
        }, "ocr");
        worker.setDaemon(true);
        worker.start();
    }

    private void onOcrFailed(HandwritingOcr.OcrException ex) {
        setOcrBusy(false);
        if (ex.badInvite) {
            ocr.setInviteCode("");
            if (askInviteCode(ex.getMessage() + "。招待コードを入力し直してください")) {
                ocrStatus.setText("招待コードを保存しました。もう一度「認識する」を押してください");
            } else {
                showHome();
            }
        } else {
            ocrStatus.setText("認識できませんでした: " + ex.getMessage());
        }
    }

    private void onOcrDone(HandwritingOcr.Result result) {
        setOcrBusy(false);
        String code = result.code() == null ? "" : result.code().strip();
        if (code.isEmpty()) {
            ocrStatus.setText("コードを読み取れませんでした");
            return;
        }
        ocrStatus.setText("認識しました。エディタ画面に読み込みました");
        loadCodeIntoEditor(code);
        showEditor();
        Platform.runLater(this::renderCode);

        List<String> notes = result.notes() == null ? List.of()
                : result.notes().stream().filter(s -> s != null && !s.isBlank()).toList();
        if (!notes.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(stage);
            alert.setTitle("画像認識の結果");
            alert.setHeaderText("読み取りに自信がない箇所があります。コードを確認してください");
            alert.setContentText("・" + String.join("\n・", notes));
            alert.show();
        }
    }

    private void loadCodeIntoEditor(String code) {
        codeArea.setText(code);
        if (editorReady) setEditorCode(code);
    }

    private Parent buildEditorScreen() {
        simView = new WebView();
        simEngine = simView.getEngine();
        simEngine.getLoadWorker().stateProperty().addListener((obs, oldS, s) -> {
            if (s == Worker.State.SUCCEEDED) simReady = true;
        });
        simEngine.load(getClass().getResource("/simulate.html").toExternalForm());

        codeArea = new TextArea(SAMPLE);
        codeArea.textProperty().addListener((obs, oldV, v) -> updatePreviewButton(v));
        codeArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 13px;");

        editorView = new WebView();
        editorEngine = editorView.getEngine();
        editorEngine.getLoadWorker().stateProperty().addListener((obs, oldS, s) -> {
            if (s == Worker.State.SUCCEEDED) {
                Platform.runLater(() -> {
                    JSObject win = (JSObject) editorEngine.executeScript("window");
                    win.setMember("bridge", bridge);
                    editorEngine.executeScript("notifyEditorIfReady()");
                });
            } else if (s == Worker.State.FAILED) {
                useFallbackEditor("エディタのページを読み込めませんでした");
            }
        });
        editorEngine.load(getClass().getResource("/editor.html").toExternalForm());

        view = new WebView();
        engine = view.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, oldS, s) -> {
            if (s == Worker.State.SUCCEEDED) {
                Platform.runLater(() -> {
                    JSObject win = (JSObject) engine.executeScript("window");
                    win.setMember("bridge", bridge);
                    engine.executeScript("notifyIfReady()");
                });
            } else if (s == Worker.State.FAILED) {
                status.setText("ページの読み込みに失敗しました");
            }
        });
        engine.load(getClass().getResource("/host.html").toExternalForm());

        Button renderBtn = Icons.button(Icons.convert(), "ブロックに変換");
        renderBtn.setOnAction(e -> renderCode());

        previewBtn = Icons.button(Icons.cube(), "3Dプレビュー");
        previewBtn.setDisable(true);
        previewBtn.setOnAction(e -> openPreview3D());

        Button blankBtn = new Button("引数をすべて空欄");
        blankBtn.setOnAction(e -> js("blankAll()"));
        Button clearBtn = new Button("空欄を解除");
        clearBtn.setOnAction(e -> js("clearBlanks()"));
        Button pdfBtn = Icons.button(Icons.pdf(), "PDF出力（問題＋解答）");
        pdfBtn.setOnAction(e -> exportPdf());

        Button svgBtn = new Button("SVG保存");
        svgBtn.setOnAction(e -> js("dumpSvg()"));

        Button vertBtn = new Button("縦に並べる");
        vertBtn.setOnAction(e -> js("arrangeVertical()"));
        Button horizBtn = new Button("横に並べる");
        horizBtn.setOnAction(e -> js("arrangeHorizontal()"));
        Button resetPosBtn = new Button("位置をリセット");
        resetPosBtn.setOnAction(e -> js("resetLayout()"));

        Button zoomOutBtn = new Button("－");
        zoomOutBtn.setOnAction(e -> stepZoom(-1));
        Button zoomInBtn = new Button("＋");
        zoomInBtn.setOnAction(e -> stepZoom(1));
        Button fitBtn = new Button("ウィンドウに合わせる");
        fitBtn.setOnAction(e -> fitZoom());
        zoomLabel.setMinWidth(48);
        zoomLabel.setAlignment(Pos.CENTER);

        Button backBtn = new Button("← 戻る");
        backBtn.setOnAction(e -> showHome());

        for (Button b : new Button[] { blankBtn, clearBtn, pdfBtn, svgBtn, vertBtn, horizBtn, resetPosBtn,
                zoomOutBtn, zoomInBtn, fitBtn }) {
            b.disableProperty().bind(rendered.not());
        }
        zoomLabel.disableProperty().bind(rendered.not());

        ToolBar bar = new ToolBar(backBtn, new Separator(), renderBtn, previewBtn, new Separator(), blankBtn, clearBtn,
                new Separator(), pdfBtn, svgBtn);
        ToolBar viewBar = new ToolBar(new Label("表示倍率: "), zoomOutBtn, zoomLabel, zoomInBtn, fitBtn,
                new Separator(), new Label("配置: "), vertBtn, horizBtn, resetPosBtn);

        view.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown() || e.getDeltaY() == 0 || !rendered.get()) return;
            applyZoom(e.getDeltaY() > 0 ? zoom * 1.1 : zoom / 1.1);
            e.consume();
        });

        BorderPane previewPane = new BorderPane(view);
        previewPane.setTop(viewBar);
        split = new SplitPane(editorView, previewPane);
        split.setDividerPositions(0.35);

        BorderPane root = new BorderPane(split);
        root.setTop(bar);
        BorderPane.setMargin(status, new Insets(4, 8, 4, 8));
        root.setBottom(status);
        return root;
    }

    private void applyZoom(double z) {
        zoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, z));
        view.setZoom(zoom);
        zoomLabel.setText(Math.round(zoom * 100) + "%");
    }

    private void stepZoom(int dir) {
        double eps = 1e-6;
        if (dir > 0) {
            for (double s : ZOOM_STEPS) {
                if (s > zoom + eps) { applyZoom(s); return; }
            }
            applyZoom(ZOOM_MAX);
        } else {
            for (int i = ZOOM_STEPS.length - 1; i >= 0; i--) {
                if (ZOOM_STEPS[i] < zoom - eps) { applyZoom(ZOOM_STEPS[i]); return; }
            }
            applyZoom(ZOOM_MIN);
        }
    }

    private void fitZoom() {
        double w = svgSize("width");
        double h = svgSize("height");
        if (w <= 0 || h <= 0) {
            status.setText("先にブロック変換をしてください");
            return;
        }
        double pad = 48;
        double fit = Math.min((view.getWidth() - pad) / w, (view.getHeight() - pad) / h);
        applyZoom(fit);
    }

    private double svgSize(String attr) {
        try {
            Object v = engine.executeScript(
                    "(function(){var s=document.querySelector('#out svg');"
                    + "if(!s) return 0;"
                    + "return parseFloat(s.getAttribute('" + attr + "'))"
                    + "||s.getBoundingClientRect()." + attr + ";})()");
            return v instanceof Number ? ((Number) v).doubleValue() : 0;
        } catch (Exception ex) {
            return 0;
        }
    }

    private void renderCode() {
        try {
            editorJs("clearErrorMarkers()");
            JSObject win = (JSObject) engine.executeScript("window");
            win.setMember("pendingCode", codeText());
            engine.executeScript("renderCode(pendingCode)");
        } catch (Exception ex) {
            status.setText("エラー: " + ex.getMessage());
        }
    }

    private String codeText() {
        if (editorReady) {
            try {
                Object v = editorEngine.executeScript("getCode()");
                if (v instanceof String) return (String) v;
            } catch (Exception ignored) {
            }
        }
        return codeArea.getText();
    }

    private void setEditorCode(String code) {
        try {
            JSObject win = (JSObject) editorEngine.executeScript("window");
            win.setMember("pendingCode", code);
            editorEngine.executeScript("setCode(pendingCode)");
        } catch (Exception ignored) {
        }
    }

    private void editorJs(String script) {
        if (!editorReady) return;
        try {
            editorEngine.executeScript(script);
        } catch (Exception ignored) {
        }
    }

    private void updatePreviewButton(String code) {
        if (previewBtn == null) return;
        previewBtn.setDisable(code == null || !PLACE_CALL.matcher(code).find());
    }

    private void openPreview3D() {
        if (!simReady) {
            status.setText("シミュレータの準備中です。少し待ってからもう一度押してください");
            return;
        }
        File exe = viewerExe();
        if (exe == null) {
            status.setText("3Dビューア（" + VIEWER_NAME + "）が見つかりません"
                    + "（開発中は viewer フォルダで cargo build --release を実行してください）");
            return;
        }
        String json;
        try {
            JSObject win = (JSObject) simEngine.executeScript("window");
            win.setMember("pendingCode", codeText());
            Object v = simEngine.executeScript("simulate(pendingCode)");
            if (!(v instanceof String)) {
                status.setText("コードの実行結果を取得できませんでした");
                return;
            }
            json = (String) v;
        } catch (Exception ex) {
            status.setText("コードを実行できませんでした: " + ex.getMessage());
            return;
        }
        try {
            File out = File.createTempFile("makecode-preview", ".json");
            out.deleteOnExit();
            java.nio.file.Files.writeString(out.toPath(), json, StandardCharsets.UTF_8);
            if (previewProcess != null && previewProcess.isAlive()) previewProcess.destroy();
            previewProcess = new ProcessBuilder(exe.getAbsolutePath(), out.getAbsolutePath()).start();
            status.setText("3Dプレビューを開きました（別ウィンドウ）");
        } catch (Exception ex) {
            status.setText("3Dプレビューを起動できませんでした: " + ex.getMessage());
        }
    }

    private static File viewerExe() {
        String prop = System.getProperty(VIEWER_PROPERTY);
        if (prop != null && !prop.isBlank() && new File(prop).isFile()) return new File(prop);
        File dir = appDir();
        if (dir != null) {
            File beside = new File(dir, VIEWER_NAME);
            if (beside.isFile()) return beside;
        }
        File dev = new File(VIEWER_DEV);
        return dev.isFile() ? dev : null;
    }

    private static File appDir() {
        try {
            File src = new File(MakeCodeBlankApp.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return src.isFile() ? src.getParentFile() : src;
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public void stop() {
        if (previewProcess != null) previewProcess.destroy();
    }

    private void useFallbackEditor(String reason) {
        if (editorReady || split == null || split.getItems().get(0) == codeArea) return;
        split.getItems().set(0, codeArea);
        status.setText(reason + "。簡易入力欄に切り替えました");
    }

    private void markCodeError(String raw) {
        if (!editorReady || raw == null) return;
        Matcher m = ERROR_POS.matcher(raw);
        if (!m.find()) return;
        int line = 1, column = 1;
        if (m.group(1) != null) {
            line = Integer.parseInt(m.group(1));
            column = Integer.parseInt(m.group(2));
        } else if (m.group(3) != null) {
            line = Integer.parseInt(m.group(3));
            column = Integer.parseInt(m.group(4));
        } else if (m.group(5) != null) {
            line = Integer.parseInt(m.group(5));
        }
        try {
            JSObject win = (JSObject) editorEngine.executeScript("window");
            win.setMember("pendingError", raw);
            editorEngine.executeScript("setErrorMarker(" + line + ", " + column + ", pendingError)");
        } catch (Exception ignored) {
        }
    }

    private void js(String script) {
        try {
            engine.executeScript(script);
        } catch (Exception ex) {
            status.setText("エラー: " + ex.getMessage());
        }
    }

    private void exportPdf() {
        if (exporting) return;
        exporting = true;
        status.setText("PDF用の画像を作成中…");
        js("exportImages(" + SCALE + ")");
    }

    private void onPdfImages(String questionB64, String answerB64) {
        BufferedImage question, answer;
        try {
            question = decodePng(questionB64);
            answer = decodePng(answerB64);
        } catch (IOException ex) {
            exporting = false;
            status.setText("PDFの作成に失敗しました: " + ex.getMessage());
            return;
        }

        String questionTitle = prefs.get(PREF_TITLE_Q, DEFAULT_TITLE_Q);
        String answerTitle = prefs.get(PREF_TITLE_A, DEFAULT_TITLE_A);
        if (prefs.getBoolean(PREF_PDF_DIALOG, true)) {
            String[] titles = PdfExport.editTitles(stage, question, answer, questionTitle, answerTitle, SCALE);
            if (titles == null) {
                exporting = false;
                status.setText("PDF出力をキャンセルしました");
                return;
            }
            questionTitle = titles[0];
            answerTitle = titles[1];
        }

        FileChooser fc = new FileChooser();
        fc.setTitle("PDFの保存先");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        fc.setInitialFileName("worksheet.pdf");
        File file = fc.showSaveDialog(stage);
        if (file == null) {
            exporting = false;
            status.setText("PDF出力をキャンセルしました");
            return;
        }

        String qt = questionTitle, at = answerTitle;
        status.setText("PDFを保存中…");
        Thread worker = new Thread(() -> {
            String message;
            try {
                PdfExport.write(file, PdfExport.compose(question, qt, SCALE),
                        PdfExport.compose(answer, at, SCALE), SCALE);
                message = "保存しました: " + file.getAbsolutePath() + "（1ページ目：問題 / 2ページ目：解答）";
            } catch (Exception ex) {
                message = "PDFの保存に失敗しました: " + ex.getMessage();
            }
            String done = message;
            Platform.runLater(() -> {
                exporting = false;
                status.setText(done);
            });
        }, "pdf");
        worker.setDaemon(true);
        worker.start();
    }

    private static BufferedImage decodePng(String b64) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
        if (img == null) throw new IOException("画像を読み込めませんでした");
        return img;
    }

    /** JavaScript から呼び出されるメソッド群（public 必須） */
    public class Bridge {
        public void onReady() {
            status.setText("準備完了：コードを入力して「ブロックに変換」を押してください");
        }

        public void onStatus(String s) {
            status.setText(s);
        }

        public void onError(String s) {
            status.setText("エラー: " + s);
            exporting = false;
        }

        public void onEditorReady(String info) {
            if (editorReady) return;
            editorReady = true;
            System.out.println("[editor] " + info);
            Platform.runLater(() -> setEditorCode(codeArea.getText()));
        }

        public void onEditorInfo(String info) {
            System.out.println("[editor] " + info);
        }

        public void onEditorFailed(String s) {
            useFallbackEditor("エディタを読み込めませんでした（" + s + "）");
        }

        public void onCodeError(String raw) {
            Platform.runLater(() -> markCodeError(raw));
        }

        public void onCodeShape(boolean hasPlace) {
            if (previewBtn != null) previewBtn.setDisable(!hasPlace);
        }

        public void onRendered(boolean ok) {
            rendered.set(ok);
            if (ok) Platform.runLater(MakeCodeBlankApp.this::applyRenderDefaults);
        }

        public void onExport(String q, String a) {
            Platform.runLater(() -> onPdfImages(q, a));
        }

        public void onSvg(String xml){
            FileChooser fc = new FileChooser();
            fc.setTitle("SVGの保存先");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG", "*.svg"));
            fc.setInitialFileName("blocks.svg");
            File f = fc.showSaveDialog(stage);

            if (f == null) return;
            try{
                java.nio.file.Files.writeString(f.toPath(), xml, StandardCharsets.UTF_8);
                status.setText("SVGを保存しました: " + f.getAbsolutePath());
            } catch(Exception ex){
                status.setText("SVGの保存に失敗しました: " + ex.getMessage());
            }
        }
    }
}
