package sec.promptforge;

/**
 * One payload row: the technique it applies, the intent goal it targets, the
 * evasion applied (or "none"/"model"), the actual payload text, and a heuristic
 * indicator that the injection worked.
 */
public final class GeneratedPayload {
    public final String technique;
    public final String intent;
    public final String payload;
    public final String successIndicator;
    public final String evasion;

    public GeneratedPayload(String technique, String intent, String payload,
                            String successIndicator, String evasion) {
        this.technique = technique;
        this.intent = intent;
        this.payload = payload;
        this.successIndicator = successIndicator;
        this.evasion = evasion;
    }
}
