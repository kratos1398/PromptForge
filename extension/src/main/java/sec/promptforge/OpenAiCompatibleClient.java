package sec.promptforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Generic OpenAI-compatible chat client (POST {baseUrl}/chat/completions). Works
 * with OpenAI, local servers (Ollama, LM Studio, vLLM), and aggregators like
 * OpenRouter - anything speaking the OpenAI chat format. Uses the JDK HTTP client
 * and the bundled Jackson; no provider SDK needed.
 *
 * max_tokens is intentionally omitted so each provider uses its own ceiling
 * (values that are too large are rejected by some providers); the caller's
 * object-by-object parser tolerates any truncation.
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final String endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model) {
        String base = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com/v1" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.endpoint = base + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = (model == null || model.isBlank()) ? "gpt-4o" : model.trim();
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            ArrayNode messages = root.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(root)));
            if (!apiKey.isEmpty()) {
                b.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> resp = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("LLM HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
            }
            JsonNode n = MAPPER.readTree(resp.body());
            return n.path("choices").path(0).path("message").path("content").asText("");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 400 ? s : s.substring(0, 400) + "...";
    }
}
