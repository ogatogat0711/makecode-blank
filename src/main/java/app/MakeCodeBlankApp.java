package app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import netscape.javascript.JSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    static final String TITLE = "MakeCode 穴埋めブロック作成";

    static final String SAMPLE = """
            player.onChat("run", function () {
                for (let index = 0; index < 5; index++) {
                    blocks.place(GOLD_BLOCK, pos(0, index, 0))
                }
                player.say("完成！")
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
    private TextArea codeArea;
    private SplitPane split;
    private boolean editorReady;
    private Scene scene;
    private Parent homeRoot;
    private Parent editorRoot;
    private Parent ocrRoot;
    private Stage stage;
    private File pendingPdf;
    private double zoom = 1.0;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        editorRoot = buildEditorScreen();
        homeRoot = buildHomeScreen();
        ocrRoot = buildOcrScreen();

        scene = new Scene(homeRoot, 1200, 750);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!e.isControlDown() || scene.getRoot() != editorRoot) return;
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
        showHome();
        stage.show();
    }

    private void showScreen(Parent root, String subtitle) {
        scene.setRoot(root);
        stage.setTitle(subtitle == null ? TITLE : TITLE + " － " + subtitle);
    }

    private void showHome()   { showScreen(homeRoot, null); }
    private void showEditor() { showScreen(editorRoot, "JavaScript から作成"); }
    private void showOcr()    { showScreen(ocrRoot, "画像認識から作成"); }

    private Parent buildHomeScreen() {
        Label title = new Label(TITLE);
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");
        Label lead = new Label("MakeCode のプログラムをブロック図にして、穴埋め教材の PDF を作ります");
        lead.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");

        Button codeBtn = homeChoice("JavaScript コードから問題を作る",
                "コードを貼り付けてブロック図に変換します");
        codeBtn.setOnAction(e -> showEditor());
        Button ocrBtn = homeChoice("画像認識から問題を作る",
                "手書きの設計書の画像から作ります（画像認識は未実装）");
        ocrBtn.setOnAction(e -> showOcr());

        VBox box = new VBox(16, title, lead, new Separator(), codeBtn, ocrBtn);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(40));
        box.setMaxWidth(Region.USE_PREF_SIZE);

        StackPane pane = new StackPane(box);
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
        Button backBtn = new Button("← 最初の画面");
        backBtn.setOnAction(e -> showHome());
        ToolBar bar = new ToolBar(backBtn);

        Label note = new Label("画像認識からの問題作成はまだ実装されていません");
        note.setStyle("-fx-font-size: 15px;");
        Label detail = new Label("手書きの設計書を読み取ってブロックにする機能がここに入ります（OCR.md 参照）");
        detail.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        VBox box = new VBox(8, note, detail);
        box.setAlignment(Pos.CENTER);

        BorderPane root = new BorderPane(box);
        root.setTop(bar);
        return root;
    }

    private Parent buildEditorScreen() {
        codeArea = new TextArea(SAMPLE);
        codeArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 13px;");

        editorView = new WebView();
        editorEngine = editorView.getEngine();
        editorEngine.getLoadWorker().stateProperty().addListener((obs, oldS, s) -> {
            if (s == Worker.State.SUCCEEDED) {
                JSObject win = (JSObject) editorEngine.executeScript("window");
                win.setMember("bridge", bridge);
                editorEngine.executeScript("notifyEditorIfReady()");
            } else if (s == Worker.State.FAILED) {
                useFallbackEditor("エディタのページを読み込めませんでした");
            }
        });
        editorEngine.load(getClass().getResource("/editor.html").toExternalForm());

        view = new WebView();
        engine = view.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, oldS, s) -> {
            if (s == Worker.State.SUCCEEDED) {
                JSObject win = (JSObject) engine.executeScript("window");
                win.setMember("bridge", bridge);
                engine.executeScript("notifyIfReady()");
            } else if (s == Worker.State.FAILED) {
                status.setText("ページの読み込みに失敗しました");
            }
        });
        engine.load(getClass().getResource("/host.html").toExternalForm());

        Button renderBtn = new Button("ブロックに変換");
        renderBtn.setOnAction(e -> renderCode());
        CheckBox repeatChk = kindBox("繰り返し回数", "repeat", true);
        CheckBox numberChk = kindBox("数値", "number", false);
        CheckBox dirChk = kindBox("方向", "direction", true);
        CheckBox varChk = kindBox("変数", "variable", true);
        CheckBox textChk = kindBox("文字列", "text", false);
        CheckBox otherChk = kindBox("その他", "other", false);
        CheckBox[] kindChecks = { repeatChk, numberChk, dirChk, varChk, textChk, otherChk };

        Button targetBtn = new Button("チェックした種類を空欄");
        targetBtn.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for(CheckBox c : kindChecks){
                if (c.isSelected()){
                    if(sb.length() > 0)
                        sb.append(',');
                    sb.append(c.getUserData());
                }
            }
            js("blankKinds('" + sb +"')");
        });


        Button blankBtn = new Button("引数をすべて空欄");
        blankBtn.setOnAction(e -> js("blankAll()"));
        Button clearBtn = new Button("空欄を解除");
        clearBtn.setOnAction(e -> js("clearBlanks()"));
        Button pdfBtn = new Button("PDF出力（問題＋解答）");
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

        Button backBtn = new Button("← 最初の画面");
        backBtn.setOnAction(e -> showHome());

        ToolBar bar = new ToolBar(backBtn, new Separator(), renderBtn, new Separator(), targetBtn, blankBtn, clearBtn,
                new Separator(), new Label("配置: "), vertBtn, horizBtn, resetPosBtn,
                new Separator(), pdfBtn, new Separator(), svgBtn);
        ToolBar kindBar = new ToolBar(new Label("空欄対象: "), repeatChk, numberChk, dirChk, varChk, textChk, otherChk,
                new Separator(), new Label("表示倍率: "), zoomOutBtn, zoomLabel, zoomInBtn, fitBtn);

        view.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown() || e.getDeltaY() == 0) return;
            applyZoom(e.getDeltaY() > 0 ? zoom * 1.1 : zoom / 1.1);
            e.consume();
        });

        split = new SplitPane(editorView, view);
        split.setDividerPositions(0.35);

        BorderPane root = new BorderPane(split);
        root.setTop(new VBox(bar, kindBar));
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

    private static CheckBox kindBox(String label, String kind, boolean selected){
        CheckBox c = new CheckBox(label);
        c.setUserData(kind);
        c.setSelected(selected);
        return c;
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
        FileChooser fc = new FileChooser();
        fc.setTitle("PDFの保存先");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        fc.setInitialFileName("worksheet.pdf");
        File f = fc.showSaveDialog(stage);
        if (f == null) return;
        pendingPdf = f;
        status.setText("PDFを作成中…");
        js("exportImages(" + SCALE + ")");
    }

    private void writePdf(String questionB64, String answerB64) {
        if (pendingPdf == null) return;
        try (PDDocument doc = new PDDocument()) {
            addImagePage(doc, Base64.getDecoder().decode(questionB64), "question");
            addImagePage(doc, Base64.getDecoder().decode(answerB64), "answer");
            doc.save(pendingPdf);
            status.setText("保存しました: " + pendingPdf.getAbsolutePath() + "（1ページ目：問題 / 2ページ目：解答）");
        } catch (Exception ex) {
            status.setText("PDFの保存に失敗しました: " + ex.getMessage());
        } finally {
            pendingPdf = null;
        }
    }

    private static void addImagePage(PDDocument doc, byte[] png, String name) throws Exception {
        PDImageXObject img = PDImageXObject.createFromByteArray(doc, png, name);
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);

        float maxW = page.getMediaBox().getWidth() - 2 * MARGIN;
        float maxH = page.getMediaBox().getHeight() - 2 * MARGIN;
        // 画面上の1px = 0.75pt として等倍配置、はみ出す場合のみ縮小
        float w = img.getWidth() / (float) SCALE * 0.75f;
        float h = img.getHeight() / (float) SCALE * 0.75f;
        float k = Math.min(1f, Math.min(maxW / w, maxH / h));
        w *= k;
        h *= k;

        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(img, MARGIN, page.getMediaBox().getHeight() - MARGIN - h, w, h);
        }
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
            pendingPdf = null;
        }

        public void onEditorReady(String info) {
            if (editorReady) return;
            editorReady = true;
            System.out.println("[editor] " + info);
            Platform.runLater(() -> setEditorCode(SAMPLE));
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

        public void onExport(String q, String a) {
            writePdf(q, a);
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
