package app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

public class HandwritingOcr {

    static final String DEFAULT_SERVER_URL = "https://makecode-ocr-cwdsacfffa-an.a.run.app/";
    static final String SERVER_URL_ENV = "MAKECODE_OCR_URL";
    static final int MAX_EDGE = 1568;
    static final int MAX_IMAGES = 6;
    static final float JPEG_QUALITY = 0.9f;
    static final Duration TIMEOUT = Duration.ofSeconds(150);
    static final String PREF_INVITE = "ocrInviteCode";

    public record Result(String code, List<String> notes, int remaining) {
    }

    public static class OcrException extends Exception {
        final boolean badInvite;

        OcrException(String message) {
            this(message, false);
        }

        OcrException(String message, boolean badInvite) {
            super(message);
            this.badInvite = badInvite;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Preferences prefs = Preferences.userNodeForPackage(HandwritingOcr.class);

    static String serverUrl() {
        String env = System.getenv(SERVER_URL_ENV);
        String url = env != null && !env.isBlank() ? env.strip() : DEFAULT_SERVER_URL;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    static boolean hasServer() {
        return !serverUrl().isEmpty();
    }

    String inviteCode() {
        return prefs.get(PREF_INVITE, "");
    }

    void setInviteCode(String code) {
        if (code == null || code.isBlank()) prefs.remove(PREF_INVITE);
        else prefs.put(PREF_INVITE, code.strip());
    }

    static BufferedImage load(File file) throws OcrException {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) throw new OcrException("この形式の画像は読み込めません（JPEG / PNG を使ってください）");
            return img;
        } catch (IOException ex) {
            throw new OcrException("画像を読み込めませんでした: " + ex.getMessage());
        }
    }

    static BufferedImage rotateRight(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.translate(h, 0);
        g.rotate(Math.PI / 2);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    static byte[] toJpeg(BufferedImage src, int maxEdge) throws IOException {
        double k = Math.min(1.0, (double) maxEdge / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * k));
        int h = Math.max(1, (int) Math.round(src.getHeight() * k));
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(dst, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    Result recognize(List<BufferedImage> images) throws OcrException {
        if (!hasServer()) {
            throw new OcrException("認識サーバーの URL が設定されていません");
        }
        String invite = inviteCode();
        if (invite.isEmpty()) {
            throw new OcrException("招待コードが設定されていません", true);
        }
        if (images.isEmpty()) {
            throw new OcrException("画像がありません");
        }
        if (images.size() > MAX_IMAGES) {
            throw new OcrException("画像は一度に " + MAX_IMAGES + " 枚までです");
        }

        String body;
        try {
            List<Map<String, String>> parts = new ArrayList<>();
            for (BufferedImage image : images) {
                parts.add(Map.of(
                        "image", Base64.getEncoder().encodeToString(toJpeg(image, MAX_EDGE)),
                        "mediaType", "image/jpeg"));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("images", parts);
            if (parts.size() == 1) payload.putAll(parts.get(0));
            body = JSON.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new OcrException("画像の変換に失敗しました: " + ex.getMessage());
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(serverUrl() + "/v1/recognize"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + invite)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new OcrException("認識サーバーの応答がありませんでした（時間切れ）");
        } catch (IOException ex) {
            throw new OcrException("認識サーバーに接続できませんでした。ネット接続を確認してください");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OcrException("認識が中断されました");
        }

        int status = response.statusCode();
        JsonNode json;
        try {
            json = JSON.readTree(response.body());
        } catch (IOException ex) {
            throw new OcrException("認識サーバーの応答を読めませんでした（" + status + "）");
        }
        if (status != 200) {
            String message = json.path("error").asText("認識サーバーでエラーが起きました（" + status + "）");
            throw new OcrException(message, status == 401);
        }
        List<String> notes = new ArrayList<>();
        for (JsonNode n : json.path("notes")) notes.add(n.asText());
        return new Result(json.path("code").asText(""), notes, json.path("remaining").asInt(-1));
    }
}
