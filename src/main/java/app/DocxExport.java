package app;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class DocxExport {

    static final int EMU_PER_PT = 12700;
    static final int TWIP_PER_PT = 20;
    /** Word の段落ぶんに残しておく高さ（pt）。詰めすぎると空白ページが挟まる */
    static final float SLACK_PT = 36;

    private DocxExport() {
    }

    static void write(File file, List<PdfExport.Page> pages, int scale) throws IOException {
        List<BufferedImage> blocks = new ArrayList<>();
        for (PdfExport.Page p : pages) blocks.add(p.blocks());
        List<byte[]> images = encodeAll(blocks);
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.setLevel(1);
            put(zip, "[Content_Types].xml", contentTypes().getBytes(StandardCharsets.UTF_8));
            put(zip, "_rels/.rels", rootRels().getBytes(StandardCharsets.UTF_8));
            put(zip, "word/_rels/document.xml.rels", documentRels(pages.size()).getBytes(StandardCharsets.UTF_8));
            put(zip, "word/document.xml", document(pages, scale).getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i) == null) throw new IOException("画像を作れませんでした");
                put(zip, "word/media/image" + (i + 1) + ".png", images.get(i));
            }
        }
    }

    static List<byte[]> encodeAll(List<BufferedImage> pages) {
        return pages.parallelStream().map(img -> {
            try {
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(img, "png", png);
                return png.toByteArray();
            } catch (IOException ex) {
                return null;
            }
        }).toList();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Override PartName=\"/word/document.xml\""
                + " ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
    }

    private static String rootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\""
                + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.xml\"/>"
                + "</Relationships>";
    }

    private static String documentRels(int count) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= count; i++) {
            sb.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"")
                    .append(" Target=\"media/image").append(i).append(".png\"/>");
        }
        return sb.append("</Relationships>").toString();
    }

    private static String document(List<PdfExport.Page> pages, int scale) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<w:document")
                .append(" xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"")
                .append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"")
                .append(" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"")
                .append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"")
                .append(" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"")
                .append(" xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">")
                .append("<w:body>");

        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            PdfExport.Page page = pages.get(i);
            sb.append(titleBox(page, scale, 1000 + i));
            sb.append(picture(page.blocks(), page.title(), scale, i + 1));
            sb.append("<w:p/>");
        }

        float margin = MakeCodeBlankApp.MARGIN;
        sb.append("<w:sectPr><w:pgSz w:w=\"").append(round(PdfExport.PAGE_W * TWIP_PER_PT))
                .append("\" w:h=\"").append(round(PdfExport.PAGE_H * TWIP_PER_PT)).append("\"/>")
                .append("<w:pgMar w:top=\"").append(round(margin * TWIP_PER_PT))
                .append("\" w:right=\"").append(round(margin * TWIP_PER_PT))
                .append("\" w:bottom=\"").append(round(margin * TWIP_PER_PT))
                .append("\" w:left=\"").append(round(margin * TWIP_PER_PT))
                .append("\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr>")
                .append("</w:body></w:document>");
        return sb.toString();
    }

    /** 見出しの帯の高さ（pt）。ページに置いたときの縮尺に合わせる */
    private static float headPt(BufferedImage img, String title, int scale) {
        if (title == null || title.isBlank()) return 0;
        return PdfExport.HEAD_UNITS * scale * PdfExport.ptPerPx(scale) * shrink(img, title, scale);
    }

    /**
     * Word は段落そのものにも高さがあるので、A4 から SLACK_PT を引いた範囲に収める。
     * 引かないと画像が 1 ページに収まらず、間に空白ページが挟まる（実測）。
     */
    private static float shrink(BufferedImage img, String title, int scale) {
        int head = title == null || title.isBlank() ? 0 : PdfExport.HEAD_UNITS * scale;
        float ptPerPx = PdfExport.ptPerPx(scale);
        float maxW = PdfExport.PAGE_W - 2 * MakeCodeBlankApp.MARGIN;
        float maxH = PdfExport.PAGE_H - 2 * MakeCodeBlankApp.MARGIN - SLACK_PT;
        float w = img.getWidth() * ptPerPx;
        float h = (img.getHeight() + head) * ptPerPx;
        return Math.min(1f, Math.min(maxW / w, maxH / h));
    }

    /** 見出しは画像に焼き込まず、Word で編集できるテキストボックスにする */
    private static String titleBox(PdfExport.Page page, int scale, int id) {
        String title = page.title();
        if (title == null || title.isBlank()) return "";
        float k = shrink(page.blocks(), title, scale);
        float boxH = headPt(page.blocks(), title, scale);
        float boxW = PdfExport.PAGE_W - 2 * MakeCodeBlankApp.MARGIN;
        int halfPoints = Math.max(2, Math.round(PdfExport.TITLE_UNITS * 0.75f * k * 2));
        long cx = (long) (boxW * EMU_PER_PT);
        long cy = (long) (boxH * EMU_PER_PT);
        return "<w:p>" + noSpacing()
                + "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>"
                + "<wp:docPr id=\"" + id + "\" name=\"見出し " + id + "\"/>"
                + "<wp:cNvGraphicFramePr/>"
                + "<a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
                + "<wps:wsp><wps:cNvSpPr txBox=\"1\"/>"
                + "<wps:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"
                + "<a:noFill/><a:ln><a:noFill/></a:ln></wps:spPr>"
                + "<wps:txbx><w:txbxContent><w:p>" + noSpacing()
                + "<w:r><w:rPr><w:b/><w:sz w:val=\"" + halfPoints + "\"/>"
                + "<w:szCs w:val=\"" + halfPoints + "\"/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + escape(title) + "</w:t></w:r></w:p></w:txbxContent></wps:txbx>"
                + "<wps:bodyPr rot=\"0\" vert=\"horz\" wrap=\"square\""
                + " lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\" anchor=\"t\" anchorCtr=\"0\">"
                + "<a:noAutofit/></wps:bodyPr>"
                + "</wps:wsp></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
    }

    private static String noSpacing() {
        return "<w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String picture(BufferedImage img, String title, int scale, int id) {
        float k = shrink(img, title, scale);
        long cx = (long) (img.getWidth() * PdfExport.ptPerPx(scale) * k * EMU_PER_PT);
        long cy = (long) (img.getHeight() * PdfExport.ptPerPx(scale) * k * EMU_PER_PT);
        return "<w:p>" + noSpacing() + "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>"
                + "<wp:docPr id=\"" + id + "\" name=\"ブロック図 " + id + "\"/>"
                + "<wp:cNvGraphicFramePr/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"" + id + "\" name=\"image" + id + ".png\"/>"
                + "<pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"rId" + id + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
    }

    private static long round(double v) {
        return Math.round(v);
    }
}
