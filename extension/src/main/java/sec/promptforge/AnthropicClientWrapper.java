package sec.promptforge;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ThinkingConfigAdaptive;

/**
 * Thin wrapper around the official Anthropic Java SDK. Everything SDK-specific
 * lives here, so if a symbol name drifts between SDK versions the fix is local.
 *
 * Model: claude-opus-4-8 with adaptive thinking. Output is set to the model's
 * 128K ceiling and the request is STREAMED, so payload generation is never
 * truncated by a smaller budget and large responses don't hit HTTP timeouts.
 */
public final class AnthropicClientWrapper implements LlmClient {

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;

    public AnthropicClientWrapper(String apiKey, String model) {
        this.model = (model == null || model.isBlank()) ? "claude-opus-4-8" : model;
        // Sonnet 4.6 output caps at 64K; the Opus/Fable family go to 128K.
        this.maxTokens = this.model.contains("sonnet") ? 64000L : 128000L;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    /** Single-shot completion (streamed). Returns the concatenated text content. */
    public String complete(String systemPrompt, String userPrompt) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(systemPrompt)
                .addUserMessage(userPrompt)
                .build();

        StringBuilder sb = new StringBuilder();
        try (StreamResponse<RawMessageStreamEvent> stream = client.messages().createStreaming(params)) {
            stream.stream()
                    .flatMap(event -> event.contentBlockDelta().stream())
                    .flatMap(delta -> delta.delta().text().stream())
                    .forEach(textDelta -> sb.append(textDelta.text()));
        }
        return sb.toString();
    }
}
