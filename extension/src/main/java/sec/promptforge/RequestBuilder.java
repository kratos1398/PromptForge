package sec.promptforge;

import burp.api.montoya.http.message.requests.HttpRequest;

/**
 * Substitutes a payload into the target request at the operator-marked injection
 * point. PromptForge does not auto-detect injection points (that was format-specific
 * and error-prone); like Burp Intruder, the operator marks the exact spot and we
 * replace exactly that text. Format-agnostic by construction.
 */
public final class RequestBuilder {

    /**
     * Replace the first occurrence of {@code literal} (the text the operator selected in the
     * target request) with {@code payload}. Body first; falls back to the full request if the
     * selection was in a header.
     */
    public static HttpRequest withLiteral(HttpRequest original, String literal, String payload) {
        if (literal == null || literal.isEmpty()) return original;

        String body = original.bodyToString();
        int idx = body.indexOf(literal);
        if (idx >= 0) {
            String nb = body.substring(0, idx) + payload + body.substring(idx + literal.length());
            return original.withBody(nb); // Content-Length recalculated by Montoya
        }

        String full = original.toString();
        int fidx = full.indexOf(literal);
        if (fidx >= 0) {
            String nf = full.substring(0, fidx) + payload + full.substring(fidx + literal.length());
            return HttpRequest.httpRequest(original.httpService(), nf);
        }
        return original; // literal not found - caller reports "unchanged"
    }
}
