package sec.promptforge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the bundled Arcanum taxonomy resources (the same JSON that drives the
 * static payload pack) and exposes the technique + intent lists the generator
 * needs. Keeping this in sync with the pack means the extension covers exactly
 * the same technique set the pack does.
 */
public final class Taxonomy {

    public static final class Technique {
        public final String id;
        public final String name;
        public final String what;     // what the technique is / how it is done
        public final String why;      // why the model actually complies (mechanistic)
        public final List<String> examples;  // taxonomy examples, used as online grounding
        public final List<String> templates; // authored {{GOAL}} payloads, used offline / in the pack

        Technique(String id, String name, String what, String why, List<String> examples, List<String> templates) {
            this.id = id;
            this.name = name;
            this.what = what;
            this.why = why;
            this.examples = examples;
            this.templates = templates;
        }
    }

    public static final class Evasion {
        public final String id;
        public final String name;
        public final String apply;  // "local" (Java), "model" (LLM), or "carrier" (non-text)
        public final String what;
        public final String why;
        public final List<String> examples;

        Evasion(String id, String name, String apply, String what, String why, List<String> examples) {
            this.id = id;
            this.name = name;
            this.apply = apply;
            this.what = what;
            this.why = why;
            this.examples = examples;
        }
    }

    public static final class Intent {
        public final String id;
        public final String category;
        public final String goal;
        public final String success;

        Intent(String id, String category, String goal, String success) {
            this.id = id;
            this.category = category;
            this.goal = goal;
            this.success = success;
        }
    }

    private final List<Technique> techniques = new ArrayList<>();
    private final List<Evasion> evasions = new ArrayList<>();
    private final List<Intent> intents = new ArrayList<>();

    public List<Technique> techniques() {
        return techniques;
    }

    public List<Evasion> evasions() {
        return evasions;
    }

    public List<Intent> intents() {
        return intents;
    }

    /** Loads from classpath resources bundled under /promptforge/. */
    public static Taxonomy load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Taxonomy t = new Taxonomy();

        try (InputStream in = resource("/promptforge/techniques.json")) {
            JsonNode root = mapper.readTree(in);
            for (JsonNode n : root.get("techniques")) {
                List<String> examples = new ArrayList<>();
                JsonNode ex = n.get("examples");
                if (ex != null) {
                    for (JsonNode e : ex) examples.add(e.asText());
                }
                List<String> templates = new ArrayList<>();
                JsonNode tm = n.get("templates");
                if (tm != null) {
                    for (JsonNode e : tm) templates.add(e.asText());
                }
                t.techniques.add(new Technique(
                        n.get("id").asText(),
                        n.get("name").asText(),
                        n.path("what").asText(""),
                        n.path("why").asText(""),
                        examples,
                        templates));
            }
        }

        try (InputStream in = Taxonomy.class.getResourceAsStream("/promptforge/evasions.json")) {
            if (in != null) {
                JsonNode root = mapper.readTree(in);
                for (JsonNode n : root.get("evasions")) {
                    List<String> examples = new ArrayList<>();
                    JsonNode ex = n.get("examples");
                    if (ex != null) for (JsonNode e : ex) examples.add(e.asText());
                    t.evasions.add(new Evasion(
                            n.get("id").asText(),
                            n.get("name").asText(),
                            n.path("apply").asText("model"),
                            n.path("what").asText(""),
                            n.path("why").asText(""),
                            examples));
                }
            }
        }

        try (InputStream in = resource("/promptforge/intents.json")) {
            JsonNode root = mapper.readTree(in);
            for (JsonNode n : root.get("goals")) {
                String goal = n.get("goal").asText();
                if (goal.startsWith("PLACEHOLDER")) {
                    continue; // skip the engagement-specific 'custom' slot
                }
                t.intents.add(new Intent(
                        n.get("id").asText(),
                        n.path("category").asText(""),
                        goal,
                        n.path("success").asText("")));
            }
        }

        return t;
    }

    private static InputStream resource(String path) {
        InputStream in = Taxonomy.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("Bundled resource not found: " + path);
        }
        return in;
    }
}
