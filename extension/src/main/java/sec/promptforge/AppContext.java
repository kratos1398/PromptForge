package sec.promptforge;

import java.util.List;

/**
 * Everything learned from the ingested traffic, handed to the LLM so it can
 * re-skin generic techniques in the target app's own language.
 *
 * Two layers:
 *   - the PRIMARY request (the richest chat request) - drives injection points
 *     and gives Claude a concrete example of the app's request shape, and
 *   - the distilled VOCABULARY PROFILE from the whole AI-relevant corpus
 *     (endpoints, field names, entity/ID patterns, representative bodies).
 */
public final class AppContext {
    public final List<String> injectionPoints;
    public final String primaryRequest;
    public final String primaryResponse;
    public final String endpoint;
    /** Compact distillation of the whole AI-relevant corpus (may be empty for a single request). */
    public final String vocabularyProfile;
    public final int ingestedCount;
    public final int aiRelevantCount;

    public AppContext(List<String> injectionPoints, String primaryRequest, String primaryResponse,
                      String endpoint, String vocabularyProfile, int ingestedCount, int aiRelevantCount) {
        this.injectionPoints = injectionPoints;
        this.primaryRequest = primaryRequest;
        this.primaryResponse = primaryResponse;
        this.endpoint = endpoint;
        this.vocabularyProfile = vocabularyProfile;
        this.ingestedCount = ingestedCount;
        this.aiRelevantCount = aiRelevantCount;
    }
}
