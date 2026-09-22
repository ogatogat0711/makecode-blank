package ocr;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Recognizer {

    static final String MODEL = "claude-sonnet-5";
    static final long MAX_TOKENS = 16000L;
    static final String INSTRUCTION = "この手書きの設計メモを MakeCode の JavaScript に書き起こしてください。";

    public record Result(
            @JsonPropertyDescription("MakeCode for Minecraft の JavaScript。ブロックに変換できる書き方だけを使う")
            String code,
            @JsonPropertyDescription("読み取りに自信がない箇所や推測で補った箇所。日本語で短く。無ければ空")
            List<String> notes) {
    }

    public record Outcome(Result result, long inputTokens, long outputTokens) {
    }

    public static class RecognizeException extends Exception {
        final int status;

        RecognizeException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();
    private final String systemPrompt;

    Recognizer() throws IOException {
        try (InputStream in = Recognizer.class.getResourceAsStream("/ocr-prompt.md")) {
            if (in == null) throw new IOException("ocr-prompt.md が見つかりません");
            systemPrompt = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    Outcome recognize(String base64, Base64ImageSource.MediaType mediaType) throws RecognizeException {
        StructuredMessageCreateParams<Result> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(systemPrompt)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .outputConfig(Result.class)
                .addUserMessageOfBlockParams(List.of(
                        ContentBlockParam.ofImage(ImageBlockParam.builder()
                                .source(Base64ImageSource.builder()
                                        .data(base64)
                                        .mediaType(mediaType)
                                        .build())
                                .build()),
                        ContentBlockParam.ofText(TextBlockParam.builder().text(INSTRUCTION).build())))
                .build();

        StructuredMessage<Result> response;
        try {
            response = client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException | NotFoundException ex) {
            System.err.println("[recognize] 設定エラー " + ex);
            throw new RecognizeException(500, "認識サーバーの設定に問題があります。作者に連絡してください");
        } catch (RateLimitException ex) {
            throw new RecognizeException(503, "認識サービスが混み合っています。少し待ってからもう一度試してください");
        } catch (InternalServerException ex) {
            throw new RecognizeException(502, "認識サービスで一時的なエラーが起きました。少し待ってからもう一度試してください");
        } catch (AnthropicServiceException ex) {
            System.err.println("[recognize] API エラー " + ex.statusCode() + " " + ex.getMessage());
            throw new RecognizeException(502, "認識サービスでエラーが起きました（" + ex.statusCode() + "）");
        } catch (AnthropicIoException ex) {
            throw new RecognizeException(502, "認識サービスに接続できませんでした");
        }

        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) {
            throw new RecognizeException(422, "この画像は処理を断られました。別の画像で試してください");
        }
        if (StopReason.MAX_TOKENS.equals(stop)) {
            throw new RecognizeException(422, "出力が長すぎて途中で切れました");
        }
        Result result = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new RecognizeException(502, "認識結果が空でした"));
        return new Outcome(result, response.usage().inputTokens(), response.usage().outputTokens());
    }
}
