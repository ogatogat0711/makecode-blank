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
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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
import java.util.ArrayList;
import java.util.List;

final class PdfExport {

    static final float PAGE_W = PDRectangle.A4.getWidth();
    static final float PAGE_H = PDRectangle.A4.getHeight();
    static final double PREVIEW_PX_PER_PT = 1.5;
    static final int HEAD_UNITS = 40;
    static final int TITLE_UNITS = 20;
    static final int WHITE = 245;
    static final int PREVIEW_MAX_PAGES = 30;
    /** 文字の高さの下限（MakeCodeBlankApp.TARGET_DPI のときのドット数） */
    static final int MIN_TEXT_DOTS = 15;
    /** ブロック列の区切りで切るのは、ページがこの割合より埋まるときだけ */
    static final double FILL_ENOUGH = 0.6;

    record Placement(float x, float top, float w, float h) {
    }

    /**
     * ページを分ける手がかり。groups はブロック列の上端・下端、breaks は各ブロックの上端、
     * guards は文字と空欄の矩形（x0,y0,x1,y1）、textPx は文字 1 つぶんの高さ。すべて画像の px。
     */
    record Layout(List<int[]> groups, List<int[]> sides, List<Integer> breaks, List<int[]> guards, int textPx) {
        static final Layout EMPTY = new Layout(List.of(), List.of(), List.of(), List.of(), 0);

        Layout shrunk(int factor) {
            List<int[]> g = new ArrayList<>();
            for (int[] b : groups) g.add(new int[] { b[0] / factor, b[1] / factor });
            List<int[]> s = new ArrayList<>();
            for (int[] b : sides) s.add(new int[] { b[0] / factor, b[1] / factor });
            List<Integer> t = new ArrayList<>();
            for (int b : breaks) t.add(b / factor);
            List<int[]> r = new ArrayList<>();
            for (int[] b : guards) r.add(new int[] { b[0] / factor, b[1] / factor, b[2] / factor, b[3] / factor });
            return new Layout(g, s, t, r, Math.max(1, textPx / factor));
        }
    }

    record Sheet(String title, BufferedImage blocks, Layout layout) {
        Sheet(String title, BufferedImage blocks) {
            this(title, blocks, Layout.EMPTY);
        }
    }

    private PdfExport() {
    }

    static float ptPerPx(int scale) {
        return 0.75f / scale;
    }

    static BufferedImage compose(BufferedImage blocks, String title, int scale) {
        if (title == null || title.isBlank()) return blocks;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, TITLE_UNITS * scale);
        int head = HEAD_UNITS * scale;

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
        float w = imgW * ptPerPx(scale);
        float h = imgH * ptPerPx(scale);
        float k = Math.min(1f, Math.min(maxW / w, maxH / h));
        return new Placement(margin, margin, w * k, h * k);
    }

    private static boolean blankRow(BufferedImage img, int x0, int x1, int y) {
        for (int x = x0; x < x1; x++) {
            int p = img.getRGB(x, y);
            if (((p >> 16) & 255) < WHITE || ((p >> 8) & 255) < WHITE || (p & 255) < WHITE) return false;
        }
        return true;
    }

    static List<int[]> merge(List<int[]> spans) {
        List<int[]> sorted = new ArrayList<>();
        for (int[] s : spans) if (s[1] > s[0]) sorted.add(new int[] { s[0], s[1] });
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] b : sorted) {
            if (!merged.isEmpty() && b[0] <= merged.get(merged.size() - 1)[1] + 2) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], b[1]);
            } else {
                merged.add(new int[] { b[0], b[1] });
            }
        }
        return merged;
    }

    /** 文字や空欄の内側では切らない。spans は merge() 済みであること */
    private static boolean cuttable(List<int[]> spans, int at) {
        int lo = 0, hi = spans.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int[] s = spans.get(mid);
            if (at <= s[0]) hi = mid - 1;
            else if (at >= s[1]) lo = mid + 1;
            else return false;
        }
        return true;
    }

    /**
     * 0〜total を maxSpan 以下の区間に分ける。forbidden（文字・空欄）の内側では切らず、
     * wanted（ブロック列の終わり → ブロックの境目）に近いところを優先する。
     */
    static List<int[]> cuts(int total, int maxSpan, List<int[]> forbidden, List<List<Integer>> wanted) {
        List<int[]> out = new ArrayList<>();
        List<int[]> blocked = merge(forbidden);
        int minSpan = Math.max(1, maxSpan / 4);
        int start = 0;
        while (start < total) {
            if (total - start <= maxSpan) {
                out.add(new int[] { start, total });
                break;
            }
            int limit = start + maxSpan;
            int floor = Math.max(start + minSpan, (int) (start + maxSpan * FILL_ENOUGH));
            int cut = -1;
            for (List<Integer> list : wanted) {
                for (int p : list) {
                    if (p > floor && p <= limit && p > cut && cuttable(blocked, p)) cut = p;
                }
                if (cut > 0) break;
            }
            for (int at = limit; cut < 0 && at > start + minSpan; at--) {
                if (cuttable(blocked, at)) cut = at;
            }
            if (cut < 0) cut = limit;
            out.add(new int[] { start, cut });
            start = cut;
        }
        return out;
    }

    private static int trimTop(BufferedImage img, int x0, int x1, int from, int to) {
        int y = from;
        while (y < to && blankRow(img, x0, x1, y)) y++;
        return y;
    }

    private static int trimBottom(BufferedImage img, int x0, int x1, int from, int to) {
        int y = to;
        while (y > from && blankRow(img, x0, x1, y - 1)) y--;
        return y;
    }

    static int titleWidth(String title, int scale) {
        if (title == null || title.isBlank()) return 0;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        int w = g.getFontMetrics(new Font(Font.SANS_SERIF, Font.BOLD, TITLE_UNITS * scale)).stringWidth(title);
        g.dispose();
        return w + 4 * scale;
    }

    /** 文字が小さくなりすぎないための縮小率の下限（MIN_TEXT_DOTS ドット分） */
    static float minShrink(int textPx, float ptPerPx) {
        if (textPx <= 0) return 0f;
        float wantedPt = MIN_TEXT_DOTS * 72f / MakeCodeBlankApp.TARGET_DPI;
        float naturalPt = textPx * ptPerPx;
        if (naturalPt <= 0) return 0f;
        return Math.min(1f, wantedPt / naturalPt);
    }

    /** ページごとに切り出す範囲（x0, y0, x1, y1）を決める */
    static List<int[]> areas(Sheet sheet, int scale) {
        BufferedImage blocks = sheet.blocks();
        String title = sheet.title();
        boolean titled = title != null && !title.isBlank();
        Layout layout = sheet.layout() == null ? Layout.EMPTY : sheet.layout();

        float margin = MakeCodeBlankApp.MARGIN;
        float maxWpt = PAGE_W - 2 * margin;
        float maxHpt = PAGE_H - 2 * margin;
        float ptPerPx = ptPerPx(scale);
        int width = Math.max(blocks.getWidth(), titled ? titleWidth(title, scale) : 0);
        float fit = Math.min(1f, maxWpt / (width * ptPerPx));
        float shrink = Math.max(fit, minShrink(layout.textPx(), ptPerPx));
        float headPt = titled ? HEAD_UNITS * scale * ptPerPx : 0;

        int colW = (int) Math.floor(maxWpt / (shrink * ptPerPx));
        int rowH = (int) Math.floor((maxHpt - headPt) / (shrink * ptPerPx));

        if (colW <= 0 || rowH <= 0) {
            return List.of(new int[] { 0, 0, blocks.getWidth(), blocks.getHeight() });
        }

        List<int[]> xSpans = new ArrayList<>();
        List<int[]> ySpans = new ArrayList<>();
        for (int[] g : layout.guards()) {
            xSpans.add(new int[] { g[0], g[2] });
            ySpans.add(new int[] { g[1], g[3] });
        }
        List<Integer> stackRights = new ArrayList<>();
        for (int[] s : merge(layout.sides())) stackRights.add(s[1]);
        List<int[]> columns = cuts(blocks.getWidth(), colW, xSpans, List.of(stackRights));

        List<int[]> areas = new ArrayList<>();
        for (int[] col : columns) {
            List<int[]> guards = new ArrayList<>();
            for (int[] g : layout.guards()) {
                if (g[2] > col[0] && g[0] < col[1]) guards.add(new int[] { g[1], g[3] });
            }
            if (guards.isEmpty()) guards = ySpans;
            List<Integer> groupEnds = new ArrayList<>();
            for (int[] g : merge(layout.groups())) groupEnds.add(g[1]);
            List<int[]> rows = cuts(blocks.getHeight(), rowH, guards, List.of(groupEnds, layout.breaks()));
            for (int[] row : rows) areas.add(new int[] { col[0], row[0], col[1], row[1] });
        }
        return areas;
    }

    /** 見出しを焼き込む前のページ（見出しの文字とブロック画像を分けたまま） */
    record Page(String title, BufferedImage blocks) {
    }

    static List<Page> pageParts(Sheet sheet, int scale) {
        BufferedImage blocks = sheet.blocks();
        String title = sheet.title();
        boolean titled = title != null && !title.isBlank();
        List<int[]> areas = areas(sheet, scale);

        List<BufferedImage> parts = new ArrayList<>();
        for (int[] a : areas) {
            if (a[2] - a[0] < 1) continue;
            int top = trimTop(blocks, a[0], a[2], a[1], a[3]);
            int bottom = trimBottom(blocks, a[0], a[2], top, a[3]);
            if (bottom - top < 1) continue;
            parts.add(blocks.getSubimage(a[0], top, a[2] - a[0], bottom - top));
        }
        if (parts.isEmpty()) parts.add(blocks);

        List<Page> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String pageTitle = titled && parts.size() > 1
                    ? title + "（" + (i + 1) + "/" + parts.size() + "）" : title;
            out.add(new Page(pageTitle, parts.get(i)));
        }
        return out;
    }

    static List<BufferedImage> pages(Sheet sheet, int scale) {
        List<BufferedImage> out = new ArrayList<>();
        for (Page p : pageParts(sheet, scale)) out.add(compose(p.blocks(), p.title(), scale));
        return out;
    }

    static void writePdf(File file, List<BufferedImage> pages, int scale) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (BufferedImage img : pages) addPage(doc, img, scale);
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

    static String[] editTitles(Stage owner, Sheet question, Sheet answer, String format, int scale) {
        Sheet q1 = new Sheet(question.title(), downscale(question.blocks(), scale),
                question.layout().shrunk(scale));
        Sheet a1 = new Sheet(answer.title(), downscale(answer.blocks(), scale),
                answer.layout().shrunk(scale));

        TextField qField = new TextField(question.title());
        TextField aField = new TextField(answer.title());
        Label hint = new Label("空欄にすると、そのページには見出しを付けません");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #666;");
        Label head = new Label("ページの見出し（" + format + " で出力）");
        head.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label count = new Label();
        count.setStyle("-fx-text-fill: #666;");

        Button ok = new Button("OK（保存先を選ぶ）");
        ok.setDefaultButton(true);
        Button cancel = new Button("キャンセル");
        cancel.setCancelButton(true);
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(8, cancel, ok);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox left = new VBox(10, head, new Label("問題のページ"), qField,
                new Label("解答のページ"), aField, hint, count, grow, buttons);
        left.setPadding(new Insets(20));
        left.setMinWidth(260);

        VBox pages = new VBox(16);
        pages.setPadding(new Insets(20));
        pages.setAlignment(Pos.TOP_CENTER);
        pages.setStyle("-fx-background-color: #d9dce1;");
        ScrollPane scroll = new ScrollPane(pages);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: #d9dce1; -fx-background-color: #d9dce1;");

        Runnable update = () -> {
            List<BufferedImage> qp = pages(new Sheet(qField.getText(), q1.blocks(), q1.layout()), 1);
            List<BufferedImage> ap = pages(new Sheet(aField.getText(), a1.blocks(), a1.layout()), 1);
            pages.getChildren().clear();
            addPreview(pages, scroll, "問題", qp);
            addPreview(pages, scroll, "解答", ap);
            count.setText("問題 " + qp.size() + " ページ / 解答 " + ap.size() + " ページ");
        };
        PauseTransition debounce = new PauseTransition(Duration.millis(150));
        debounce.setOnFinished(e -> update.run());
        qField.textProperty().addListener((o, x, y) -> debounce.playFromStart());
        aField.textProperty().addListener((o, x, y) -> debounce.playFromStart());
        update.run();

        SplitPane split = new SplitPane(left, scroll);
        split.setDividerPositions(0.45);

        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle(format + "出力 － 見出しとプレビュー");
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

    private static void addPreview(VBox box, ScrollPane scroll, String label, List<BufferedImage> pages) {
        int shown = Math.min(pages.size(), PREVIEW_MAX_PAGES);
        for (int i = 0; i < shown; i++) {
            Label caption = new Label(label + "  " + (i + 1) + " / " + pages.size() + " ページ");
            caption.setStyle("-fx-font-weight: bold; -fx-text-fill: #333;");
            ImageView view = new ImageView(toFx(pagePreview(pages.get(i))));
            view.setPreserveRatio(true);
            view.setSmooth(true);
            view.fitWidthProperty().bind(scroll.widthProperty().subtract(64));
            StackPane frame = new StackPane(view);
            frame.setStyle("-fx-border-color: #9aa0a6; -fx-border-width: 1;");
            frame.setMaxWidth(Region.USE_PREF_SIZE);
            box.getChildren().addAll(caption, frame);
        }
        if (shown < pages.size()) {
            Label more = new Label(label + " は全 " + pages.size() + " ページです（先頭 " + shown + " ページだけ表示しています）");
            more.setStyle("-fx-text-fill: #555;");
            box.getChildren().add(more);
        }
    }
}
