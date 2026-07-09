package sec.promptforge;

/**
 * Minimal LLM abstraction so PromptForge can target different providers.
 * Implementations: {@link AnthropicClientWrapper} (native Anthropic SDK) and
 * {@link OpenAiCompatibleClient} (OpenAI /chat/completions - covers OpenAI,
 * local servers like Ollama/LM Studio/vLLM, and aggregators like OpenRouter).
 */
public interface LlmClient {
    /** Single-shot completion. Returns the concatenated text content. */
    String complete(String systemPrompt, String userPrompt);
}
