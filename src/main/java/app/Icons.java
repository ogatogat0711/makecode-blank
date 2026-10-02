package app;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

final class Icons {

    private Icons() {
    }

    static Button button(Node icon, String tooltip) {
        Button b = new Button();
        b.setGraphic(icon);
        b.setTooltip(tooltip(tooltip));
        b.setAccessibleText(tooltip);
        b.setMinHeight(32);
        return b;
    }

    static Tooltip tooltip(String text) {
        Tooltip t = new Tooltip(text);
        t.setShowDelay(Duration.millis(250));
        return t;
    }

    static Node convert() {
        Text abc = new Text("abc");
        abc.setFont(Font.font("Consolas", FontWeight.BOLD, 12));
        abc.setFill(Color.web("#3c3c3c"));

        SVGPath arrow = new SVGPath();
        arrow.setContent("M0 5 H9 M6 2 L9 5 L6 8");
        arrow.setFill(null);
        arrow.setStroke(Color.web("#3c3c3c"));
        arrow.setStrokeWidth(1.6);
        arrow.setStrokeLineCap(StrokeLineCap.ROUND);

        SVGPath block = new SVGPath();
        block.setContent("M0 2 Q0 0 2 0 H3.5 L5.5 2.2 H9.5 L11.5 0 H18 Q20 0 20 2 V9 Q20 11 18 11"
                + " H11.5 L9.5 13.2 H5.5 L3.5 11 H2 Q0 11 0 9 Z");
        block.setFill(Color.web("#4C97FF"));
        block.setStroke(Color.web("#3373CC"));
        block.setStrokeWidth(1);

        HBox box = new HBox(3, abc, arrow, block);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    static Node cube() {
        Polygon top = face(Color.web("#F7CD5C"), 11, 0, 22, 6, 11, 12, 0, 6);
        Polygon left = face(Color.web("#D9A12B"), 0, 6, 11, 12, 11, 24, 0, 18);
        Polygon right = face(Color.web("#A9780F"), 22, 6, 11, 12, 11, 24, 22, 18);
        return new Group(top, left, right);
    }

    private static Polygon face(Color fill, double... points) {
        Polygon p = new Polygon(points);
        p.setFill(fill);
        p.setStroke(Color.web("#6E4E0A"));
        p.setStrokeWidth(0.8);
        return p;
    }

    static Node pdf() {
        SVGPath page = new SVGPath();
        page.setContent("M1 0 H12 L18 6 V23 H1 Z");
        page.setFill(Color.WHITE);
        page.setStroke(Color.web("#666666"));
        page.setStrokeWidth(1.1);

        SVGPath fold = new SVGPath();
        fold.setContent("M12 0 V6 H18");
        fold.setFill(Color.web("#E4E4E4"));
        fold.setStroke(Color.web("#666666"));
        fold.setStrokeWidth(1.1);

        Rectangle band = new Rectangle(-2, 10, 17, 9);
        band.setArcWidth(2);
        band.setArcHeight(2);
        band.setFill(Color.web("#D93025"));

        Text label = new Text("PDF");
        label.setFont(Font.font("Arial", FontWeight.BOLD, 8));
        label.setFill(Color.WHITE);
        label.setX(-0.5);
        label.setY(17.5);

        return new Group(page, fold, band, label);
    }

    static Node word() {
        SVGPath page = new SVGPath();
        page.setContent("M1 0 H12 L18 6 V23 H1 Z");
        page.setFill(Color.WHITE);
        page.setStroke(Color.web("#666666"));
        page.setStrokeWidth(1.1);

        SVGPath fold = new SVGPath();
        fold.setContent("M12 0 V6 H18");
        fold.setFill(Color.web("#E4E4E4"));
        fold.setStroke(Color.web("#666666"));
        fold.setStrokeWidth(1.1);

        Rectangle band = new Rectangle(-2, 10, 17, 9);
        band.setArcWidth(2);
        band.setArcHeight(2);
        band.setFill(Color.web("#2B579A"));

        Text label = new Text("W");
        label.setFont(Font.font("Arial", FontWeight.BOLD, 9));
        label.setFill(Color.WHITE);
        label.setX(3.5);
        label.setY(17.8);

        return new Group(page, fold, band, label);
    }

    static Node gear() {
        int teeth = 8;
        double cx = 10, cy = 10, outer = 10, inner = 7.4, hole = 3.4;
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < teeth * 4; i++) {
            double a = Math.PI * 2 * i / (teeth * 4) - Math.PI / (teeth * 4);
            double r = (i % 4 == 1 || i % 4 == 2) ? outer : inner;
            p.append(i == 0 ? "M" : "L")
                    .append(cx + r * Math.cos(a)).append(' ')
                    .append(cy + r * Math.sin(a)).append(' ');
        }
        p.append("Z M").append(cx - hole).append(' ').append(cy)
                .append(" A").append(hole).append(' ').append(hole).append(" 0 1 0 ").append(cx + hole).append(' ').append(cy)
                .append(" A").append(hole).append(' ').append(hole).append(" 0 1 0 ").append(cx - hole).append(' ').append(cy)
                .append(" Z");
        SVGPath g = new SVGPath();
        g.setContent(p.toString());
        g.setFillRule(FillRule.EVEN_ODD);
        g.setFill(Color.web("#555555"));
        return g;
    }
}
