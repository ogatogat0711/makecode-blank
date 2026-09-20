package app;

import javafx.application.Application;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
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

public class MakeCodeBlankApp extends Application {

    /** 画像化の倍率（大きいほど印刷がきれい、ファイルは重くなる） */
    static final int SCALE = 4;
    /** PDFの余白（pt） */
    static final float MARGIN = 40f;

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
    private WebEngine engine;
    private TextArea codeArea;
    private Stage stage;
    private File pendingPdf;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        codeArea = new TextArea(SAMPLE);
        codeArea.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 13px;");

        WebView view = new WebView();
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

        ToolBar bar = new ToolBar(renderBtn, new Separator(), targetBtn, blankBtn, clearBtn, new Separator(), pdfBtn, new Separator(), svgBtn);
        ToolBar kindBar = new ToolBar(new Label("空欄対象: "), repeatChk, numberChk, dirChk, varChk, textChk, otherChk);

        SplitPane split = new SplitPane(codeArea, view);
        split.setDividerPositions(0.35);

        BorderPane root = new BorderPane(split);
        root.setTop(new VBox(bar, kindBar));
        BorderPane.setMargin(status, new Insets(4, 8, 4, 8));
        root.setBottom(status);

        stage.setTitle("MakeCode 穴埋めブロック作成");
        stage.setScene(new Scene(root, 1200, 750));
        stage.show();
    }

    private static CheckBox kindBox(String label, String kind, boolean selected){
        CheckBox c = new CheckBox(label);
        c.setUserData(kind);
        c.setSelected(selected);
        return c;
    }

    private void renderCode() {
        try {
            JSObject win = (JSObject) engine.executeScript("window");
            win.setMember("pendingCode", codeArea.getText());
            engine.executeScript("renderCode(pendingCode)");
        } catch (Exception ex) {
            status.setText("エラー: " + ex.getMessage());
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
