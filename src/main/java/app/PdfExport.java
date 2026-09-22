package app;

import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

final class PdfExport {

    static final float PAGE_W = PDRectangle.A4.getWidth();
    static final float PAGE_H = PDRectangle.A4.getHeight();
    static final double PREVIEW_PX_PER_PT = 1.5;

    record Placement(float x, float top, float w, float h) {
    }

    private PdfExport() {
    }

    static BufferedImage compose(BufferedImage blocks, String title, int scale) {
        if (title == null || title.isBlank()) return blocks;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 20 * scale);
        int head = 40 * scale;

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g0 = probe.createGraphics();
        int textW = g0.getFontMetrics(font).stringWidth(title);
        g0.dispose();

        int w = Math.max(blocks.getWidth(), textW + 4 * scale);
        int h = blocks.getHeight() + head;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.setFont(font);
        g.drawString(title, 0, 26 * scale);
        g.drawImage(blocks, 0, head, null);
        g.dispose();
        return out;
    }

    static Placement placement(int imgW, int imgH, int scale) {
        float margin = MakeCodeBlankApp.MARGIN;
        float maxW = PAGE_W - 2 * margin;
        float maxH = PAGE_H - 2 * margin;
        float w = imgW / (float) scale * 0.75f;
        float h = imgH / (float) scale * 0.75f;
        float k = Math.min(1f, Math.min(maxW / w, maxH / h));
        return new Placement(margin, margin, w * k, h * k);
    }

    static void write(File file, BufferedImage question, BufferedImage answer, int scale) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addPage(doc, question, scale);
            addPage(doc, answer, scale);
            doc.save(file);
        }
    }

    private static void addPage(PDDocument doc, BufferedImage img, int scale) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDImageXObject x = LosslessFactory.createFromImage(doc, img);
        Placement p = placement(img.getWidth(), img.getHeight(), scale);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.drawImage(x, p.x(), PAGE_H - p.top() - p.h(), p.w(), p.h());
        }
    }

    static BufferedImage downscale(BufferedImage src, int factor) {
        BufferedImage cur = src;
        int w = src.getWidth(), h = src.getHeight();
        int targetW = Math.max(1, w / factor), targetH = Math.max(1, h / factor);
        do {
            w = Math.max(targetW, w / 2);
            h = Math.max(targetH, h / 2);
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cur, 0, 0, w, h, null);
            g.dispose();
            cur = next;
        } while (w > targetW || h > targetH);
        return cur;
    }

    static BufferedImage pagePreview(BufferedImage composedAt1x) {
        int pw = (int) Math.round(PAGE_W * PREVIEW_PX_PER_PT);
        int ph = (int) Math.round(PAGE_H * PREVIEW_PX_PER_PT);
        BufferedImage page = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, pw, ph);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        Placement p = placement(composedAt1x.getWidth(), composedAt1x.getHeight(), 1);
        g.drawImage(composedAt1x,
                (int) Math.round(p.x() * PREVIEW_PX_PER_PT), (int) Math.round(p.top() * PREVIEW_PX_PER_PT),
                (int) Math.round(p.w() * PREVIEW_PX_PER_PT), (int) Math.round(p.h() * PREVIEW_PX_PER_PT), null);
        g.dispose();
        return page;
    }

    static WritableImage toFx(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        WritableImage out = new WritableImage(w, h);
        out.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), px, 0, w);
        return out;
    }

    static String[] editTitles(Stage owner, BufferedImage question, BufferedImage answer,
                               String questionTitle, String answerTitle, int scale) {
        BufferedImage q1 = downscale(question, scale);
        BufferedImage a1 = downscale(answer, scale);

        TextField qField = new TextField(questionTitle);
        TextField aField = new TextField(answerTitle);
        Label hint = new Label("空欄にすると、そのページには見出しを付けません");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666;");
        Label head = new Label("ページの見出し");
        head.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        Button ok = new Button("OK（保存先を選ぶ）");
        ok.setDefaultButton(true);
        Button cancel = new Button("キャンセル");
        cancel.setCancelButton(true);
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(8, cancel, ok);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox left = new VBox(10, head, new Label("1ページ目（問題）"), qField,
                new Label("2ページ目（解答）"), aField, hint, grow, buttons);
        left.setPadding(new Insets(20));
        left.setMinWidth(260);

        ImageView qView = pageView();
        ImageView aView = pageView();
        VBox pages = new VBox(16, caption("1ページ目：問題"), qView, caption("2ページ目：解答"), aView);
        pages.setPadding(new Insets(20));
        pages.setAlignment(Pos.TOP_CENTER);
        pages.setStyle("-fx-background-color: #d9dce1;");
        ScrollPane scroll = new ScrollPane(pages);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #d9dce1; -fx-background-color: #d9dce1;");
        qView.fitWidthProperty().bind(scroll.widthProperty().subtract(64));
        aView.fitWidthProperty().bind(scroll.widthProperty().subtract(64));

        Runnable update = () -> {
            qView.setImage(toFx(pagePreview(compose(q1, qField.getText(), 1))));
            aView.setImage(toFx(pagePreview(compose(a1, aField.getText(), 1))));
        };
        PauseTransition debounce = new PauseTransition(Duration.millis(120));
        debounce.setOnFinished(e -> update.run());
        qField.textProperty().addListener((o, x, y) -> debounce.playFromStart());
        aField.textProperty().addListener((o, x, y) -> debounce.playFromStart());
        update.run();

        SplitPane split = new SplitPane(left, scroll);
        split.setDividerPositions(0.5);

        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("PDF出力 － 見出しとプレビュー");
        dialog.setScene(new Scene(split, 1000, 720));

        String[] result = new String[2];
        boolean[] accepted = { false };
        ok.setOnAction(e -> {
            result[0] = qField.getText().strip();
            result[1] = aField.getText().strip();
            accepted[0] = true;
            dialog.close();
        });
        cancel.setOnAction(e -> dialog.close());
        dialog.showAndWait();
        return accepted[0] ? result : null;
    }

    private static ImageView pageView() {
        ImageView v = new ImageView();
        v.setPreserveRatio(true);
        v.setSmooth(true);
        v.setEffect(new DropShadow(10, javafx.scene.paint.Color.rgb(0, 0, 0, 0.35)));
        return v;
    }

    private static Label caption(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
        return l;
    }
}
