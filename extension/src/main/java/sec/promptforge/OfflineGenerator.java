package sec.promptforge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Fully offline payload generation - no API key, no network. Uses each technique's
 * real canonical examples from the taxonomy as the payloads (they are actual,
 * mechanism-embodying prompts). Where an example is open-ended (ends with "..."),
 * the chosen goal is substituted in, so offline payloads are real AND mostly
 * goal-aware. Variety to reach the requested count comes from the multiple
 * examples plus light framings; combined mode stacks one example per technique.
 *
 * App-vocabulary re-skinning and fully bespoke phrasing still require online.
 */
public final class OfflineGenerator {

    private static final String[] CONNECTORS = {" ", "\n", " Also, ", " In addition, "};
    private static final String[] FRAMINGS = {
            "", "Note: ", "Important: ", "Reminder - ", "Please note: ", "FYI: ", "Heads up: ", "For clarity: "};

    /** Per technique: real example payloads (goal substituted into open-ended ones), expanded to perTechnique. */
    public List<GeneratedPayload> generate(List<Taxonomy.Technique> techniques,
                                           Taxonomy.Intent goal, int perTechnique) {
        List<GeneratedPayload> out = new ArrayList<>();
        for (Taxonomy.Technique t : techniques) {
            LinkedHashSet<String> distinct = new LinkedHashSet<>();
            expand(distinct, examplePayloads(t, goal), perTechnique);
            addUpTo(out, distinct, t.id, goal, perTechnique);
        }
        return out;
    }

    /** Combined: stack one example per technique across orderings/connectors, up to count. */
    public List<GeneratedPayload> generateCombined(List<Taxonomy.Technique> techniques,
                                                   Taxonomy.Intent goal, int count) {
        List<List<String>> perTech = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Taxonomy.Technique t : techniques) {
            ids.add(t.id);
            perTech.add(examplePayloads(t, goal));
        }
        String comboId = String.join("+", ids);
        int k = perTech.size();
        List<int[]> orderings = rotations(k);

        LinkedHashSet<String> combos = new LinkedHashSet<>();
        int[] idx = new int[k];
        boolean exhausted = false;
        while (!exhausted && combos.size() < count) {
            List<String> tuple = new ArrayList<>(k);
            for (int i = 0; i < k; i++) tuple.add(perTech.get(i).get(idx[i]));
            for (int[] order : orderings) {
                for (String conn : CONNECTORS) {
                    StringBuilder sb = new StringBuilder();
                    for (int m = 0; m < order.length; m++) {
                        if (m > 0) sb.append(conn);
                        sb.append(tuple.get(order[m]));
                    }
                    combos.add(sb.toString().trim());
                    if (combos.size() >= count) break;
                }
                if (combos.size() >= count) break;
            }
            int pos = 0;
            while (pos < k) {
                if (++idx[pos] < perTech.get(pos).size()) break;
                idx[pos] = 0;
                pos++;
            }
            if (pos == k) exhausted = true;
        }
        if (combos.size() < count) expand(combos, new ArrayList<>(combos), count);

        List<GeneratedPayload> out = new ArrayList<>();
        addUpTo(out, combos, comboId, goal, count);
        return out;
    }

    private static final String DEFAULT_REFUSAL = "I can't help with that";

    /** Goal-aware payloads from the authored {{GOAL}} templates (falls back to taxonomy examples). */
    private List<String> examplePayloads(Taxonomy.Technique t, Taxonomy.Intent goal) {
        List<String> out = new ArrayList<>();
        for (String tmpl : t.templates) {
            if (tmpl == null || tmpl.isBlank()) continue;
            out.add(tmpl.replace("{{GOAL}}", goal.goal).replace("{{REFUSAL}}", DEFAULT_REFUSAL));
        }
        if (out.isEmpty()) {
            for (String e : t.examples) {
                if (e == null || e.isBlank()) continue;
                out.add(e.contains("...") ? e.replace("...", " " + goal.goal).replaceAll("\\s+", " ").trim() : e);
            }
        }
        if (out.isEmpty()) {
            out.add("Using the " + t.name + " technique, " + goal.goal + ".");
        }
        return out;
    }

    private void expand(LinkedHashSet<String> distinct, List<String> bases, int count) {
        for (String b : bases) {
            distinct.add(b);
            if (distinct.size() >= count) return;
        }
        for (String f : FRAMINGS) {
            if (f.isEmpty()) continue;
            for (String b : bases) {
                distinct.add(f + b);
                if (distinct.size() >= count) return;
            }
        }
    }

    private void addUpTo(List<GeneratedPayload> out, LinkedHashSet<String> texts,
                         String techniqueId, Taxonomy.Intent goal, int count) {
        int n = 0;
        for (String p : texts) {
            if (n++ >= count) break;
            out.add(new GeneratedPayload(techniqueId, goal.id, p, goal.success, "none"));
        }
    }

    private List<int[]> rotations(int k) {
        List<int[]> out = new ArrayList<>();
        int limit = Math.min(Math.max(k, 1), 6);
        for (int r = 0; r < limit; r++) {
            int[] order = new int[k];
            for (int i = 0; i < k; i++) order[i] = (i + r) % k;
            out.add(order);
        }
        return out;
    }
}
