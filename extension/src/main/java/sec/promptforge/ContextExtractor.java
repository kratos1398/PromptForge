package sec.promptforge;

import burp.api.montoya.http.message.HttpRequestResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingests one OR many requests (live selection, or a Burp "Save items" XML
 * export), filters to the AI-relevant ones, and distills them into an
 * {@link AppContext}. Format-agnostic: it never rejects a request - JSON / form
 * / query strings get structured injection-point hints, everything else still
 * contributes its raw text to the vocabulary profile.
 *
 * Bulk only helps (never hurts) because we filter out non-AI noise and distill
 * to a compact profile rather than dumping raw requests at the model.
 */
public final class ContextExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] LIKELY_FIELDS = {
            "message", "messages", "prompt", "query", "input", "text",
            "question", "content", "user_input", "q", "search", "ask"
    };
    // URL/body keywords that mark a request as AI-relevant.
    private static final String[] AI_HINTS = {
            "chat", "assistant", "prompt", "complete", "completion", "conversation",
            "llm", "gpt", "/ai", "bot", "generate", "message", "ask", "model", "agent"
    };
    private static final String[] STATIC_EXT = {
            ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".woff",
            ".woff2", ".ttf", ".ico", ".map", ".webp"
    };
    // "patient id: 24345", "order number 9981", "ticket #4821", UUIDs, "c-991"
    private static final Pattern ENTITY = Pattern.compile(
            "([a-zA-Z][a-zA-Z _-]{1,24}\\s*(?:id|number|no|#)\\s*[:#]?\\s*[A-Za-z0-9-]{2,})" +
            "|([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    // ---- public ingest entry points ----

    public AppContext fromSelected(List<HttpRequestResponse> selected) {
        List<RawRequest> all = new ArrayList<>();
        for (HttpRequestResponse rr : selected) {
            all.add(RawRequest.from(rr));
        }
        // The request the operator right-clicked (first selected) is the TARGET:
        // injection points + display come from it; vocabulary comes from the whole set.
        return build(all, all.isEmpty() ? null : all.get(0));
    }

    public AppContext fromBurpXml(InputStream xml) throws Exception {
        List<RawRequest> all = parseBurpXml(xml);
        return build(all, null); // no human-chosen target -> richest becomes primary
    }

    // ---- core: filter -> pick primary -> distill ----

    private AppContext build(List<RawRequest> all, RawRequest chosenPrimary) {
        List<RawRequest> ai = new ArrayList<>();
        for (RawRequest r : all) {
            if (isAiRelevant(r)) ai.add(r);
        }
        List<RawRequest> corpus = ai.isEmpty() ? all : ai; // never end up with nothing

        RawRequest primary = chosenPrimary != null ? chosenPrimary : pickPrimary(corpus);
        String profile = distill(corpus);

        // Injection points are marked manually by the operator (Intruder-style), not auto-detected.
        return new AppContext(
                List.of(),
                primary.body.isBlank() ? "(empty body)" : primary.body,
                truncate(primary.responseBody, 3000),
                primary.method + " " + primary.url,
                profile,
                all.size(),
                ai.size());
    }

    private boolean isAiRelevant(RawRequest r) {
        String url = r.url.toLowerCase();
        for (String ext : STATIC_EXT) {
            if (url.contains(ext)) return false;
        }
        String ct = r.contentType.toLowerCase();
        if (ct.startsWith("image/") || ct.startsWith("font/")
                || ct.contains("javascript") || ct.contains("text/css")) {
            return false;
        }
        String hay = (url + " " + r.body).toLowerCase();
        for (String h : AI_HINTS) {
            if (hay.contains(h)) return true;
        }
        return false;
    }

    /** Richest AI request: one that has a detectable prompt field and the longest body. */
    private RawRequest pickPrimary(List<RawRequest> corpus) {
        RawRequest best = corpus.get(0);
        int bestScore = -1;
        for (RawRequest r : corpus) {
            int score = r.body.length() + (hasLikelyField(r) ? 100_000 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private boolean hasLikelyField(RawRequest r) {
        String b = r.body.toLowerCase();
        for (String f : LIKELY_FIELDS) {
            if (b.contains("\"" + f + "\"") || b.contains(f + "=")) return true;
        }
        return false;
    }


    // ---- distillation: compact profile of the whole AI corpus ----

    private String distill(List<RawRequest> corpus) {
        Set<String> endpoints = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
        Set<String> entities = new LinkedHashSet<>();
        List<String> samples = new ArrayList<>();

        for (RawRequest r : corpus) {
            endpoints.add(r.method + " " + stripQuery(r.url));
            collectJsonFieldNames(r.body, fields);
            collectEntities(r.body, entities);
            collectEntities(r.responseBody, entities);
            if (samples.size() < 3 && !r.body.isBlank()) {
                samples.add(truncate(r.body, 600));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ENDPOINTS (").append(endpoints.size()).append("):\n");
        endpoints.stream().limit(20).forEach(e -> sb.append("  - ").append(e).append("\n"));
        if (!fields.isEmpty()) {
            sb.append("REQUEST FIELD NAMES: ").append(String.join(", ", capped(fields, 40))).append("\n");
        }
        if (!entities.isEmpty()) {
            sb.append("ENTITY / ID SYNTAX SEEN (mirror this phrasing):\n");
            capped(entities, 15).forEach(e -> sb.append("  - ").append(e).append("\n"));
        }
        if (!samples.isEmpty()) {
            sb.append("REPRESENTATIVE BODIES:\n");
            for (String s : samples) sb.append("  >>> ").append(s.replace("\n", " ")).append("\n");
        }
        return sb.toString();
    }

    private void collectJsonFieldNames(String body, Set<String> out) {
        try {
            JsonNode n = MAPPER.readTree(body);
            collectFieldNamesRec(n, out);
        } catch (Exception ignored) { /* non-JSON body - skip */ }
    }

    private void collectFieldNamesRec(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                out.add(name);
                collectFieldNamesRec(node.get(name), out);
            });
        } else if (node.isArray()) {
            node.forEach(c -> collectFieldNamesRec(c, out));
        }
    }

    private void collectEntities(String text, Set<String> out) {
        if (text == null || text.isBlank()) return;
        Matcher m = ENTITY.matcher(text);
        int n = 0;
        while (m.find() && n < 30) {
            out.add(m.group().trim());
            n++;
        }
    }

    // ---- Burp "Save items" XML parsing (learning only; no Montoya needed) ----

    private List<RawRequest> parseBurpXml(InputStream xml) throws Exception {
        List<RawRequest> out = new ArrayList<>();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var doc = factory.newDocumentBuilder().parse(xml);
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String url = text(item, "url");
            String method = text(item, "method");
            String reqRaw = decoded(item, "request");
            String respRaw = decoded(item, "response");
            String contentType = headerValue(reqRaw, "Content-Type");
            out.add(new RawRequest(method, url, contentType, bodyOf(reqRaw), bodyOf(respRaw)));
        }
        return out;
    }

    private String text(Element item, String tag) {
        NodeList n = item.getElementsByTagName(tag);
        return n.getLength() > 0 ? n.item(0).getTextContent() : "";
    }

    private String decoded(Element item, String tag) {
        NodeList n = item.getElementsByTagName(tag);
        if (n.getLength() == 0) return "";
        Element el = (Element) n.item(0);
        String raw = el.getTextContent();
        if ("true".equalsIgnoreCase(el.getAttribute("base64"))) {
            try {
                return new String(Base64.getDecoder().decode(raw.trim()));
            } catch (Exception e) {
                return "";
            }
        }
        return raw;
    }

    private String bodyOf(String httpMessage) {
        int split = httpMessage.indexOf("\r\n\r\n");
        if (split < 0) split = httpMessage.indexOf("\n\n");
        return split >= 0 ? httpMessage.substring(split).trim() : "";
    }

    private String headerValue(String httpMessage, String name) {
        for (String line : httpMessage.split("\r?\n")) {
            if (line.isEmpty()) break;
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return "";
    }

    // ---- small helpers ----

    private boolean isLikelyField(String name) {
        String lower = name.toLowerCase();
        for (String f : LIKELY_FIELDS) {
            if (lower.equals(f) || lower.contains(f)) return true;
        }
        return false;
    }

    private boolean looksJson(String body) {
        String t = body.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private String stripQuery(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    private List<String> capped(Set<String> set, int max) {
        List<String> out = new ArrayList<>();
        for (String s : set) {
            if (out.size() >= max) break;
            out.add(s);
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }
}
