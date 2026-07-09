package sec.promptforge;

import burp.api.montoya.http.message.HttpRequestResponse;

/**
 * Format-agnostic view of one captured request/response, used purely for
 * LEARNING (vocabulary + injection-point hints). Works whether the source is a
 * live Montoya HttpRequestResponse (context menu) or a parsed Burp "Save items"
 * XML entry (file upload) - the learning path never needs to reconstruct a
 * Montoya object, because firing payloads is done manually in Intruder.
 */
public final class RawRequest {
    public final String method;
    public final String url;
    public final String contentType;
    public final String body;
    public final String responseBody;

    public RawRequest(String method, String url, String contentType, String body, String responseBody) {
        this.method = method == null ? "" : method;
        this.url = url == null ? "" : url;
        this.contentType = contentType == null ? "" : contentType;
        this.body = body == null ? "" : body;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public static RawRequest from(HttpRequestResponse rr) {
        var req = rr.request();
        String ct = req.headerValue("Content-Type");
        String resp = rr.response() != null ? rr.response().bodyToString() : "";
        return new RawRequest(req.method(), req.url(), ct, req.bodyToString(), resp);
    }
}
