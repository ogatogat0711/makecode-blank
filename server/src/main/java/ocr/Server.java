package ocr;

import com.anthropic.models.messages.Base64ImageSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

public class Server {

    static final int MAX_BODY = 6 * 1024 * 1024;
    static final long MAX_DRAIN = 32L * 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper();

    private final Invites invites;
    private final Recognizer recognizer;

    Server(Invites invites, Recognizer recognizer) {
        this.invites = invites;
        this.recognizer = recognizer;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(env("PORT", "8080"));
        Invites invites = new Invites(env("INVITE_CODES", ""), Integer.parseInt(env("DAILY_LIMIT", "30")));
        Server app = new Server(invites, new Recognizer());

        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/healthz", ex -> send(ex, 200, Map.of("ok", true)));
        http.createContext("/v1/recognize", app::handle);
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.start();
        System.out.println("[server] port=" + port + " invites=" + invites.size()
                + " dailyLimit=" + invites.dailyLimit() + " model=" + Recognizer.MODEL);
    }

    void handle(HttpExchange ex) throws IOException {
        long start = System.currentTimeMillis();
        try {
            byte[] body = readLimited(ex.getRequestBody());
            if (!"POST".equals(ex.getRequestMethod())) {
                send(ex, 405, error("POST で送ってください"));
                return;
            }
            String name = invites.nameFor(bearer(ex));
            if (name == null) {
                send(ex, 401, error("招待コードが正しくありません"));
                return;
            }
            if (body == null) {
                send(ex, 413, error("画像が大きすぎます"));
                return;
            }
            JsonNode req;
            try {
                req = JSON.readTree(body);
            } catch (IOException e) {
                send(ex, 400, error("リクエストの形式が正しくありません"));
                return;
            }
            String image = req.path("image").asText("");
            Base64ImageSource.MediaType type = mediaType(req.path("mediaType").asText("image/jpeg"));
            if (image.isEmpty() || type == null) {
                send(ex, 400, error("JPEG または PNG の画像を送ってください"));
                return;
            }

            if (!invites.tryAcquire(name)) {
                send(ex, 429, error("本日の上限（" + invites.dailyLimit() + " 回）に達しました。明日また使えます"));
                return;
            }
            try {
                Recognizer.Outcome out = recognizer.recognize(image, type);
                send(ex, 200, Map.of(
                        "code", out.result().code() == null ? "" : out.result().code(),
                        "notes", out.result().notes() == null ? java.util.List.of() : out.result().notes(),
                        "remaining", invites.remaining(name)));
                System.out.println("[recognize] " + name + " ok in=" + out.inputTokens()
                        + " out=" + out.outputTokens() + " " + (System.currentTimeMillis() - start) + "ms");
            } catch (Recognizer.RecognizeException e) {
                if (e.status >= 500) invites.release(name);
                send(ex, e.status, error(e.getMessage()));
                System.out.println("[recognize] " + name + " failed " + e.status + " "
                        + (System.currentTimeMillis() - start) + "ms");
            }
        } catch (RuntimeException e) {
            System.err.println("[recognize] 予期しないエラー " + e);
            send(ex, 500, error("認識サーバーでエラーが起きました"));
        } finally {
            ex.close();
        }
    }

    static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return h.substring(7).strip();
    }

    static Base64ImageSource.MediaType mediaType(String s) {
        switch (s) {
            case "image/jpeg": return Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png": return Base64ImageSource.MediaType.IMAGE_PNG;
            default: return null;
        }
    }

    static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        boolean tooLarge = false;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_DRAIN) return null;
            if (total > MAX_BODY) tooLarge = true;
            if (!tooLarge) out.write(buf, 0, n);
        }
        return tooLarge ? null : out.toByteArray();
    }

    static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
