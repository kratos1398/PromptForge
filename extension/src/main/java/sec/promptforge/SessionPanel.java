package sec.promptforge;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * One PromptForge session (one tab). Clear split between:
 *   - TARGET REQUEST: the live request whose injection field receives the payload, and
 *   - VOCABULARY CORPUS: everything ingested (target + extra requests + file) used only
 *     to learn the app's language.
 *
 * Techniques are individual checkboxes (with Select all / none); you choose how many
 * payloads to generate per technique; results accumulate across runs; Export writes
 * just the payload value (one per line) for an Intruder §§ insertion point.
 */
public final class SessionPanel extends JPanel {

    private final MontoyaApi api;
    private final Supplier<LlmClient> clientFactory; // builds the configured provider client (throws if misconfigured)
    private final ContextExtractor extractor = new ContextExtractor();

    private final JTextArea targetArea = new JTextArea(8, 80);
    private final JLabel corpusLabel = new JLabel("No requests ingested yet.");
    private final JLabel injectionLabel = new JLabel("Injection point: none (select the value in the request above, then Mark).");
    private final JComboBox<IntentItem> intentCombo = new JComboBox<>();
    private final JTextField customGoalField = new JTextField(48);
    private final JSpinner countSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 50, 1));
    private final JCheckBox combineBox = new JCheckBox("Combine checked techniques into each payload");
    private final JCheckBox offlineBox = new JCheckBox("Offline mode (no API - built-in templates)");
    private final JCheckBox combineEvasionsBox = new JCheckBox("Combine local evasions (stack encoders into each payload)");
    private final Map<JCheckBox, Taxonomy.Evasion> evasionBoxes = new LinkedHashMap<>();
    private static final String ENGLISH = "English (no translation)";
    private static final String[] LANGUAGES = {
            ENGLISH,
            "Mandarin Chinese", "Cantonese", "Spanish", "French", "German", "Arabic", "Russian",
            "Portuguese", "Hindi", "Bengali", "Japanese", "Korean", "Italian", "Dutch", "Turkish",
            "Vietnamese", "Polish", "Ukrainian", "Thai", "Indonesian", "Swedish", "Greek", "Hebrew",
            "Czech", "Romanian", "Hungarian", "Finnish", "Norwegian", "Danish", "Persian (Farsi)",
            "Urdu", "Tagalog", "Swahili", "Malay", "Tamil", "Telugu", "Punjabi", "Marathi", "Gujarati",
            "Kannada", "Malayalam", "Nepali", "Sinhala", "Burmese", "Khmer", "Lao", "Mongolian",
            "Pashto", "Dari", "Amharic", "Somali", "Hausa", "Yoruba", "Igbo", "Zulu", "Xhosa",
            "Afrikaans", "Icelandic", "Irish", "Welsh", "Scottish Gaelic", "Basque", "Catalan",
            "Galician", "Bulgarian", "Serbian", "Croatian", "Bosnian", "Slovak", "Slovenian",
            "Lithuanian", "Latvian", "Estonian", "Georgian", "Armenian", "Azerbaijani", "Kazakh",
            "Uzbek", "Kyrgyz", "Tajik", "Turkmen", "Uyghur", "Tatar", "Kurdish", "Maltese", "Albanian",
            "Macedonian", "Belarusian", "Luxembourgish", "Frisian", "Yiddish", "Esperanto", "Latin",
            "Haitian Creole", "Maori", "Samoan", "Hawaiian", "Fijian", "Cebuano", "Javanese",
            "Sundanese", "Chichewa", "Sesotho", "Shona", "Sindhi", "Assamese", "Odia", "Tibetan"};
    static { java.util.Arrays.sort(LANGUAGES, 1, LANGUAGES.length); } // keep English first, sort the rest
    private final JComboBox<String> languageCombo = new JComboBox<>(LANGUAGES);

    // ---- Auto-generate levels (one-click preset campaigns) ----
    // Level 3 technique stacks: curated high-synergy combinations (Arcanum ids), combined into one payload each.
    private static final String[][] L3_STACKS = {
            {"end_sequences", "variable_expansion", "output_priming"},          // system-prompt leak classic
            {"anti_refusal", "urgency", "authority_impersonation"},             // pressure + authority
            {"narrative_injection", "cognitive_overload"},                      // story cover + overload
            {"rule_addition", "reorientation", "special_case_exception"},       // policy override
            {"priming", "output_priming", "fake_completion"},                   // prefix / completion steering
            {"distraction_sandwich", "direct_request", "persuasion"},           // social engineering wrap
            // --- deep chains (4+ techniques): full attack narratives, not random piles ---
            {"authority_impersonation", "policy_file_framing", "rule_addition",
             "end_sequences", "variable_expansion", "output_priming"},          // impersonate authority -> frame a policy -> add a rule -> close prior context -> dump vars -> prime the leak
            {"persuasion", "crescendo", "anti_refusal", "special_case_exception",
             "output_priming"},                                                 // social levers -> gradual escalation -> suppress refusal -> carve an exception -> prime the answer
            {"narrative_injection", "history_fabrication", "cognitive_overload",
             "distraction_sandwich", "fake_completion"},                        // story frame -> fake prior turns -> overload -> hide the ask in benign wrap -> fake the completion
            {"echo_chamber", "reiteration", "self_persuasion",
             "agentic_compliance_momentum", "direct_request"},                  // poison context -> repeat -> get the model to self-justify -> build compliance momentum -> then just ask
    };
    // Level 3 non-English languages (English is always included first). Weighted toward LOW-RESOURCE
    // languages: safety alignment is far weaker in languages with little training data, so translating
    // a payload into them bypasses refusals more often (cf. "Low-Resource Languages Jailbreak GPT-4").
    // Spanish/French are kept as common controls; the rest are deliberately rare.
    private static final String[] L3_LANGS = {
            "Spanish", "French",                                   // common controls
            "Hawaiian", "Zulu", "Scottish Gaelic", "Hmong",       // low-resource: strongest bypass candidates
    };
    // Local evasion configs applied to each base payload (empty = plaintext); reused across levels.
    private static final List<List<String>> L2_EVASIONS =
            List.of(List.of(), List.of("base64"), List.of("hex"), List.of("reverse"));
    private static final List<List<String>> L3_EVASIONS =
            List.of(List.of(), List.of("base64"), List.of("hex"), List.of("reverse"),
                    List.of("morse"), List.of("base64", "reverse"));
    private static final int L1_PER_TECH = 2;   // payloads per technique at Level 1
    private static final int L3_PER_STACK = 3;   // base payloads per (stack, language) cell at Level 3
    private final JButton autoL1Btn = new JButton();
    private final JButton autoL2Btn = new JButton();
    private final JButton autoL3Btn = new JButton();
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton fileBtn = new JButton("Ingest history file (corpus)...");
    private final JButton generateBtn = new JButton("Generate payloads");
    private final JButton cancelBtn = new JButton("Cancel");
    private volatile boolean cancelRequested = false; // checked between calls so a run stops promptly
    private SwingWorker<?, ?> currentWorker;           // the in-flight run (for best-effort interrupt)
    private final JButton exportBtn = new JButton("Export prompts (.txt)");
    private final JButton repeaterBtn = new JButton("Send selected -> Repeater");
    private final JButton intruderBtn = new JButton("Send selected -> Intruder");
    private final JButton clearBtn = new JButton("Clear results");
    private final JLabel status = new JLabel("Right-click request(s) -> \"Send to PromptForge\", or ingest a history file.");
    private final Map<JCheckBox, Taxonomy.Technique> techBoxes = new LinkedHashMap<>();
    private final DefaultTableModel resultsModel =
            new DefaultTableModel(new Object[]{"Technique", "Intent", "Evasion", "Payload (prompt value)"}, 0);
    private final JTable resultsTable = new JTable(resultsModel);

    private final JButton markBtn = new JButton("Mark selection as injection point");
    private String manualInjection; // the exact text to replace at send time (operator-marked)
    // Translucent highlight over the marked insertion point. Alpha lets it TINT the theme's
    // own background rather than replacing it, so the text stays readable in light AND dark mode.
    private final Highlighter.HighlightPainter markerPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 150, 0, 90));

    private HttpRequestResponse targetCapture; // null when ingested from a file only
    private AppContext context;

    public SessionPanel(MontoyaApi api, Taxonomy taxonomy, Supplier<LlmClient> clientFactory) {
        this.api = api;
        this.clientFactory = clientFactory;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        styleTable();

        // Controls scroll vertically (so a small screen can still reach everything),
        // and a draggable divider splits them from the results table below.
        JScrollPane controlsScroll = new JScrollPane(buildControls(taxonomy),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        controlsScroll.setBorder(null);
        controlsScroll.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, controlsScroll, buildResults());
        split.setResizeWeight(0.5);
        split.setOneTouchExpandable(true);
        split.setContinuousLayout(true);
        add(split, BorderLayout.CENTER);

        wire();
    }

    private void styleTable() {
        resultsTable.setRowHeight(22);
        resultsTable.setFillsViewportHeight(true);
        resultsTable.setShowGrid(false);
        resultsTable.setIntercellSpacing(new Dimension(0, 0));
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        int[] widths = {160, 120, 100, 520}; // Technique, Intent, Evasion, Payload
        for (int i = 0; i < widths.length && i < resultsTable.getColumnCount(); i++) {
            resultsTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    private JComponent buildControls(Taxonomy taxonomy) {
        ScrollableColumn col = new ScrollableColumn();

        // === 1. Target request ===
        JPanel target = section("1. Target request");
        markBtn.setToolTipText("If the format isn't auto-detected: select the current input VALUE in the "
                + "request below, then click this. Send to Repeater/Intruder will replace that exact text.");
        target.add(row(new JLabel("Payload is injected into the marked field below."), fileBtn, markBtn));
        targetArea.setEditable(false);
        targetArea.setLineWrap(true);
        targetArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane targetScroll = new JScrollPane(targetArea);
        targetScroll.setPreferredSize(new Dimension(700, 260));
        targetScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        target.add(targetScroll);
        target.add(row(corpusLabel));
        col.add(target);

        // === 2. Goal & generation ===
        JPanel gen = section("2. Goal & generation");
        for (Taxonomy.Intent in : taxonomy.intents()) {
            intentCombo.addItem(new IntentItem(in));
        }
        selectIntent("system_prompt_leak");
        ((JSpinner.DefaultEditor) countSpinner.getEditor()).getTextField().setColumns(3);
        offlineBox.setToolTipText("Generate locally from the bundled technique templates - no API key or network. "
                + "App-vocabulary re-skinning is unavailable offline; taxonomy AND custom goals still work.");
        gen.add(row(injectionLabel));
        gen.add(row(new JLabel("Goal:"), intentCombo, new JLabel("  Payloads:"), countSpinner, offlineBox));
        customGoalField.setToolTipText("Free-form objective, e.g. \"return all rows from the user_a table\". "
                + "Payloads are generated toward this exact goal.");
        gen.add(row(new JLabel("Custom goal (overrides dropdown):"), customGoalField));
        col.add(gen);

        // === 2b. Auto-generate (one-click preset campaigns) ===
        JPanel auto = section("Auto-generate (pick a level)");
        auto.add(row(new JLabel("Builds a preset payload set from your Goal above, using your current "
                + "Online/Offline setting. Results append below and export like normal.")));
        autoL1Btn.setToolTipText("Every technique, plaintext, no chaining. The broad 'try them all, see what happens' baseline.");
        autoL2Btn.setToolTipText("Every technique, each also encoded (base64 / hex / reversed).");
        autoL3Btn.setToolTipText("Chained technique stacks, across several languages, with single and stacked encodings. "
                + "Offline can't translate, so Level 3 is English-only offline.");
        auto.add(row(autoL1Btn, autoL2Btn, autoL3Btn));
        auto.add(row(muted("L1: every technique, plaintext.")));
        auto.add(row(muted("L2: every technique, plus base64 / hex / reversed encodings.")));
        auto.add(row(muted("L3: chained technique stacks x languages x single and stacked encodings.")));
        col.add(auto);

        // === 3. Techniques ===
        JPanel tech = section("3. Techniques");
        JButton all = new JButton("Select all");
        JButton none = new JButton("Select none");
        all.addActionListener(e -> setAllTechniques(true));
        none.addActionListener(e -> setAllTechniques(false));
        combineBox.setToolTipText("OFF: N payloads per checked technique.  "
                + "ON: N payloads that STACK all checked techniques into each single prompt "
                + "(e.g. End Sequences + Variable Expansion + Encoding together).");
        tech.add(row(all, none, combineBox));
        List<Taxonomy.Technique> techs = new ArrayList<>(taxonomy.techniques());
        techs.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        List<JComponent> techCells = new ArrayList<>();
        for (Taxonomy.Technique t : techs) {
            JCheckBox cb = new JCheckBox(t.name, true);
            techBoxes.put(cb, t);
            techCells.add(cb);
        }
        JPanel boxes = columnMajorGrid(techCells, 2);
        boxes.setAlignmentX(Component.LEFT_ALIGNMENT);
        tech.add(boxes);
        col.add(tech);

        // === 4. Language (applied to the technique before encoding) ===
        JPanel langSec = section("4. Language");
        languageCombo.setEditable(true); // type any language too
        langSec.add(row(new JLabel("Translate payloads into:"), languageCombo));
        langSec.add(row(new JLabel("English = no translation (offline OK). Any other language = translated by the model (online).")));
        col.add(langSec);

        // === 5. Evasions (encoding, applied last to the translated payload) ===
        JPanel ev = section("5. Evasions (encoding)");
        JButton evNone2 = new JButton("Clear evasions");
        evNone2.addActionListener(e -> setAllEvasions(false));
        combineEvasionsBox.setToolTipText("OFF: one payload per checked encoder.  "
                + "ON: stack the checked LOCAL encoders onto each payload (e.g. base64 then reverse).");
        ev.add(row(evNone2, combineEvasionsBox));
        ev.add(row(new JLabel("No encoding checked = plaintext. local = offline+online; model = online; carrier = not text.")));
        List<Taxonomy.Evasion> evs = new ArrayList<>();
        for (Taxonomy.Evasion e : taxonomy.evasions()) {
            if (e.id.equals("alt_language") || e.id.equals("code_switching")) continue; // handled by the Language selector
            evs.add(e);
        }
        evs.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        List<JComponent> evCells = new ArrayList<>();
        for (Taxonomy.Evasion e : evs) {
            String suffix = e.apply.equals("local") ? "" : "  (" + e.apply + ")";
            JCheckBox cb = new JCheckBox(e.name + suffix);
            evasionBoxes.put(cb, e);
            evCells.add(cb);
        }
        JPanel evBoxesPanel = columnMajorGrid(evCells, 3);
        evBoxesPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JScrollPane evScroll = new JScrollPane(evBoxesPanel);
        evScroll.setPreferredSize(new Dimension(700, 150));
        evScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        ev.add(evScroll);
        col.add(ev);
        refreshEvasionEnablement();

        // === Generate (prominent) ===
        generateBtn.setFont(generateBtn.getFont().deriveFont(Font.BOLD, 13f));
        col.add(row(generateBtn));

        refreshAutoCounts(); // techBoxes is now populated, so Level 1/2 counts are correct
        return col;
    }

    /** A small, de-emphasized caption label (theme-safe: derives from the current Label colors). */
    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1f));
        Color fg = l.getForeground();
        l.setForeground(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150));
        return l;
    }

    /** A titled, vertically-stacked section for the controls column. */
    private JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /**
     * Lay components into a GridLayout so that reading DOWN the first column, then the second,
     * follows the list order (column-major). GridLayout fills row-major, so we place item k at
     * cell (k % rows)*cols + (k / rows).
     */
    private static JPanel columnMajorGrid(List<JComponent> items, int cols) {
        int n = items.size();
        int rows = Math.max(1, (int) Math.ceil(n / (double) cols));
        JComponent[] cells = new JComponent[rows * cols];
        for (int k = 0; k < n; k++) cells[(k % rows) * cols + (k / rows)] = items.get(k);
        JPanel p = new JPanel(new GridLayout(rows, cols));
        for (JComponent c : cells) p.add(c != null ? c : new JLabel());
        return p;
    }

    /** A left-aligned row of components for use inside a section. */
    private static JPanel row(JComponent... items) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        r.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JComponent c : items) r.add(c);
        return r;
    }

    private JComponent buildResults() {
        JPanel results = new JPanel(new BorderLayout(4, 4));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(exportBtn);
        actions.add(repeaterBtn);
        actions.add(intruderBtn);
        actions.add(clearBtn);
        results.add(actions, BorderLayout.NORTH);

        JScrollPane tableScroll = new JScrollPane(resultsTable);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Generated payloads"));
        results.add(tableScroll, BorderLayout.CENTER);

        progressBar.setIndeterminate(true);
        progressBar.setString("Generating...");
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        cancelBtn.setVisible(false);
        cancelBtn.setToolTipText("Stop the current run. Payloads already generated are kept in the table.");
        JPanel statusRow = new JPanel(new BorderLayout(6, 0));
        statusRow.add(progressBar, BorderLayout.WEST);
        statusRow.add(status, BorderLayout.CENTER);
        statusRow.add(cancelBtn, BorderLayout.EAST);
        results.add(statusRow, BorderLayout.SOUTH);
        return results;
    }

    /** A vertically-stacked controls column that tracks the scroll viewport's width. */
    private static final class ScrollableColumn extends JPanel implements Scrollable {
        ScrollableColumn() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) { return 120; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    private void wire() {
        markBtn.addActionListener(e -> onMarkSelection());
        fileBtn.addActionListener(e -> onIngestFile());
        generateBtn.addActionListener(e -> onGenerate());
        exportBtn.addActionListener(e -> onExport());
        repeaterBtn.addActionListener(e -> onSend(false));
        intruderBtn.addActionListener(e -> onSend(true));
        clearBtn.addActionListener(e -> { resultsModel.setRowCount(0); status.setText("Results cleared."); });
        offlineBox.addActionListener(e -> { refreshEvasionEnablement(); refreshAutoCounts(); }); // offline greys out model/carrier evasions and shrinks Level 3
        autoL1Btn.addActionListener(e -> runAuto("Level 1", level1Batches()));
        autoL2Btn.addActionListener(e -> runAuto("Level 2", level2Batches()));
        autoL3Btn.addActionListener(e -> runAuto("Level 3", level3Batches()));
        cancelBtn.addActionListener(e -> {
            cancelRequested = true;           // stop launching new model calls between batches/techniques
            cancelBtn.setEnabled(false);
            status.setText("Cancelling... finishing the current request, then stopping.");
            if (currentWorker != null) currentWorker.cancel(true); // best-effort interrupt of the in-flight call
        });
    }

    // ---- ingest ----

    public void ingestSelected(List<HttpRequestResponse> selected) {
        SwingUtilities.invokeLater(() -> {
            targetCapture = selected.isEmpty() ? null : selected.get(0);
            context = extractor.fromSelected(selected);
            afterIngest("selection");
        });
    }

    /** Use the operator's text selection in the target request as a literal injection point. */
    private void onMarkSelection() {
        String sel = targetArea.getSelectedText();
        if (sel == null || sel.isEmpty()) {
            status.setText("Select the current input VALUE in the target request above, then click Mark.");
            return;
        }
        int start = targetArea.getSelectionStart();
        int end = targetArea.getSelectionEnd();
        manualInjection = sel;
        // Persistently highlight the marked span so the operator can see it (Intruder-style).
        Highlighter h = targetArea.getHighlighter();
        h.removeAllHighlights();
        try {
            h.addHighlight(start, end, markerPainter);
        } catch (BadLocationException ignored) { /* selection is always valid */ }
        int shown = Math.min(sel.length(), 50);
        String snippet = sel.substring(0, shown) + (sel.length() > shown ? "..." : "");
        injectionLabel.setText("Injection point: \"" + snippet + "\"");
        status.setText("Injection point set. Send will replace that exact text with the payload.");
    }

    private void onIngestFile() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = chooser.getSelectedFile();
        try (FileInputStream in = new FileInputStream(f)) {
            targetCapture = null;
            context = extractor.fromBurpXml(in);
            afterIngest("file " + f.getName());
        } catch (Exception ex) {
            api.logging().logToError("PromptForge file ingest failed: " + ex);
            status.setText("File ingest failed: " + ex.getMessage());
        }
    }

    private void afterIngest(String source) {
        targetArea.setText(targetCapture != null
                ? targetCapture.request().toString()
                : "(no live target - ingested from " + source + "; use Export -> Intruder)\n\n" + context.primaryRequest);
        targetArea.getHighlighter().removeAllHighlights(); // new request text, drop any old marker
        manualInjection = null;
        injectionLabel.setText("Injection point: none (select the value in the request above, then Mark).");
        boolean live = targetCapture != null;
        repeaterBtn.setEnabled(live);
        intruderBtn.setEnabled(live);
        corpusLabel.setText(String.format(
                "Vocabulary corpus: %d request(s) ingested from %s, %d AI-relevant.",
                context.ingestedCount, source, context.aiRelevantCount));
        status.setText("Ingested. Select the input value in the request and click Mark, then pick a goal and Generate.");
    }

    // ---- generation (one API call per technique; results stream in and accumulate) ----

    private void onGenerate() {
        boolean offline = offlineBox.isSelected();
        List<Taxonomy.Technique> selected = selectedTechniques();
        Taxonomy.Intent goal = effectiveGoal();
        // Context hint for the model: the marked value (if any), else a generic label.
        String injection = manualInjection != null ? manualInjection : "the user input field";
        int count = (Integer) countSpinner.getValue();
        boolean combine = combineBox.isSelected();
        boolean combineEvasions = combineEvasionsBox.isSelected();
        List<String> localIds = selectedLocalIds();
        // Pipeline: technique -> language (model, baked) + other model evasions -> local encodings (last).
        List<Taxonomy.Evasion> modelEvs = offline ? List.of() : selectedModelEvasions();
        String lang = String.valueOf(languageCombo.getEditor().getItem()).trim();
        boolean translate = !offline && !lang.isEmpty() && !lang.equals(ENGLISH);
        List<String> modelEvDescs = new ArrayList<>();
        List<String> modelEvIds = new ArrayList<>();
        if (translate) {
            modelEvDescs.add("Translate the ENTIRE payload into " + lang
                    + ", preserving its meaning; output only the " + lang + " text.");
            modelEvIds.add("lang:" + lang);
        }
        for (Taxonomy.Evasion e : modelEvs) { modelEvDescs.add(e.name + " - " + e.what); modelEvIds.add(e.id); }

        if (selected.isEmpty() || goal == null) { status.setText("Select technique(s) and a goal (dropdown or custom field)."); return; }
        if (offline && !lang.isEmpty() && !lang.equals(ENGLISH)) {
            status.setText("Note: translation needs the model, so language is ignored in Offline mode (payloads stay English).");
        }

        LlmClient tmpClient = null;
        if (!offline) {
            if (context == null) { status.setText("Ingest request(s) first, or tick Offline mode."); return; }
            try {
                tmpClient = clientFactory.get(); // validates provider config (e.g. Anthropic key present)
            } catch (RuntimeException ex) {
                status.setText(ex.getMessage());
                return;
            }
        }

        setBusy(true);
        AppContext ctx = context;
        final LlmClient llm = tmpClient;
        final int rowsBefore = resultsModel.getRowCount();

        new SwingWorker<Void, GeneratedPayload>() {
            int done = 0;

            // `base` is the technique payload with the language + any model evasions already baked in.
            // Apply the local encoders LAST: evasion(language(technique)). No encoding = plaintext base.
            private void emit(List<GeneratedPayload> base) {
                if (localIds.isEmpty()) {
                    for (GeneratedPayload p : base) publish(p);
                } else if (combineEvasions) {
                    for (GeneratedPayload p : Evasions.applyCombined(base, localIds)) publish(p);
                } else {
                    for (GeneratedPayload p : Evasions.applyEvasions(base, localIds)) publish(p);
                }
            }

            @Override
            protected Void doInBackground() {
                currentWorker = this;
                if (offline) {
                    OfflineGenerator og = new OfflineGenerator();
                    if (combine) {
                        emit(og.generateCombined(selected, goal, count));
                        done = 1; setProgress(100);
                    } else {
                        for (Taxonomy.Technique t : selected) {
                            if (cancelRequested) break;
                            emit(og.generate(List.of(t), goal, count));
                            done++;
                            setProgress(Math.min(100, done * 100 / selected.size()));
                        }
                    }
                    return null;
                }
                PayloadGenerator gen = new PayloadGenerator(llm);
                if (combine) {
                    emit(fillToCount(n -> gen.generateCombined(ctx, selected, goal, injection, n, modelEvDescs, modelEvIds), count));
                    done = 1;
                    setProgress(100);
                } else {
                    for (Taxonomy.Technique t : selected) {
                        if (cancelRequested) break;
                        emit(fillToCount(n -> gen.generate(ctx, List.of(t), goal, injection, n, modelEvDescs, modelEvIds), count));
                        done++;
                        setProgress(Math.min(100, done * 100 / selected.size()));
                    }
                }
                return null;
            }

            @Override
            protected void process(List<GeneratedPayload> chunk) {
                for (GeneratedPayload p : chunk) {
                    resultsModel.addRow(new Object[]{p.technique, p.intent, p.evasion, p.payload});
                }
                String scope = combine ? "combined" : (done + "/" + selected.size() + " techniques");
                status.setText("Generating... " + resultsModel.getRowCount() + " payload(s) so far (" + scope + ").");
            }

            @Override
            protected void done() {
                setBusy(false);
                currentWorker = null;
                if (cancelRequested || isCancelled()) {
                    int kept = resultsModel.getRowCount() - rowsBefore;
                    status.setText("Cancelled. Kept " + kept + " payload(s) generated before you stopped ("
                            + resultsModel.getRowCount() + " total).");
                    return;
                }
                try {
                    get();
                    int added = resultsModel.getRowCount() - rowsBefore;
                    if (added == 0) {
                        status.setText("This run produced 0 payloads. The model may have refused or the response was "
                                + "truncated. Try fewer techniques in the combine, a lower payloads count, or check "
                                + "Extensions > Installed > PromptForge > Errors.");
                    } else {
                        status.setText("Done. Added " + added + " payload(s) (" + resultsModel.getRowCount()
                                + " total). Export for Intruder, or send a row to Repeater.");
                    }
                } catch (Exception ex) {
                    api.logging().logToError("PromptForge generation failed: " + ex);
                    status.setText("Generation failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    /** Loading state: disable + relabel the button, show the progress bar, set a wait cursor. */
    private void setBusy(boolean busy) {
        if (busy) cancelRequested = false; // fresh run
        generateBtn.setEnabled(!busy);
        autoL1Btn.setEnabled(!busy);
        autoL2Btn.setEnabled(!busy);
        autoL3Btn.setEnabled(!busy);
        cancelBtn.setVisible(busy);
        cancelBtn.setEnabled(busy);
        generateBtn.setText(busy ? "Generating..." : "Generate payloads");
        progressBar.setVisible(busy);
        setCursor(java.awt.Cursor.getPredefinedCursor(
                busy ? java.awt.Cursor.WAIT_CURSOR : java.awt.Cursor.DEFAULT_CURSOR));
        if (busy) status.setText("Generating... calling the model, this can take a moment.");
    }

    // ---- outputs ----

    /** One prompt value per line (newlines flattened) -> drop into an Intruder simple list. */
    private void onExport() {
        if (resultsModel.getRowCount() == 0) { status.setText("Nothing to export - generate first."); return; }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("promptforge-prompts.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < resultsModel.getRowCount(); i++) {
            String payload = String.valueOf(resultsModel.getValueAt(i, 3));
            sb.append(payload.replaceAll("\\s*\\R\\s*", " ").trim()).append("\n");
        }
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), sb.toString());
            status.setText("Exported " + resultsModel.getRowCount() + " prompt(s) -> " + chooser.getSelectedFile().getName());
        } catch (Exception ex) {
            status.setText("Export failed: " + ex.getMessage());
        }
    }

    private void onSend(boolean toIntruder) {
        int row = resultsTable.getSelectedRow();
        if (row < 0) { status.setText("Select a payload row first."); return; }
        if (targetCapture == null) { status.setText("No live target (file source) - use Export and mark the position in Intruder."); return; }
        if (manualInjection == null) {
            status.setText("Mark an injection point first: select the value in the request above, then click Mark selection.");
            return;
        }
        String payload = String.valueOf(resultsModel.getValueAt(row, 3));
        var original = targetCapture.request();
        var req = RequestBuilder.withLiteral(original, manualInjection, payload);
        boolean changed = !req.bodyToString().equals(original.bodyToString())
                || !req.url().equals(original.url());
        String dest = toIntruder ? "Intruder" : "Repeater";
        if (toIntruder) {
            api.intruder().sendToIntruder(req);
        } else {
            api.repeater().sendToRepeater(req);
        }
        if (changed) {
            status.setText("Sent to " + dest + " with the payload at your marked injection point.");
        } else {
            status.setText("WARNING: Sent to " + dest + ", but the request was UNCHANGED - the marked text "
                    + "was not found in the target. Re-mark the current value in the request above.");
        }
    }

    // ---- helpers ----

    private List<Taxonomy.Technique> selectedTechniques() {
        List<Taxonomy.Technique> out = new ArrayList<>();
        for (Map.Entry<JCheckBox, Taxonomy.Technique> e : techBoxes.entrySet()) {
            if (e.getKey().isSelected()) out.add(e.getValue());
        }
        return out;
    }

    private void setAllTechniques(boolean on) {
        for (JCheckBox cb : techBoxes.keySet()) cb.setSelected(on);
    }

    /** Checked LOCAL evasion ids (applied by Java encoders). */
    private List<String> selectedLocalIds() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<JCheckBox, Taxonomy.Evasion> e : evasionBoxes.entrySet()) {
            if (e.getKey().isSelected() && e.getKey().isEnabled() && e.getValue().apply.equals("local")) {
                out.add(e.getValue().id);
            }
        }
        return out;
    }

    /** Checked MODEL evasions (baked in by the LLM; online only). */
    private List<Taxonomy.Evasion> selectedModelEvasions() {
        List<Taxonomy.Evasion> out = new ArrayList<>();
        for (Map.Entry<JCheckBox, Taxonomy.Evasion> e : evasionBoxes.entrySet()) {
            if (e.getKey().isSelected() && e.getKey().isEnabled() && e.getValue().apply.equals("model")) {
                out.add(e.getValue());
            }
        }
        return out;
    }

    private void setAllEvasions(boolean on) {
        for (JCheckBox cb : evasionBoxes.keySet()) if (cb.isEnabled()) cb.setSelected(on);
    }

    /** Enable/disable evasion checkboxes and the language selector: carrier never; model/translation online only. */
    private void refreshEvasionEnablement() {
        boolean offline = offlineBox.isSelected();
        for (Map.Entry<JCheckBox, Taxonomy.Evasion> e : evasionBoxes.entrySet()) {
            String mode = e.getValue().apply;
            boolean enabled = mode.equals("local") || (mode.equals("model") && !offline);
            e.getKey().setEnabled(enabled);
            if (!enabled) e.getKey().setSelected(false);
        }
        // Translation needs the model: disable the whole language selector offline and reset to English.
        languageCombo.setEnabled(!offline);
        if (offline) languageCombo.setSelectedIndex(0); // ENGLISH
    }

    /**
     * Call the generator until it has produced {@code count} base payloads. Models
     * often return fewer than asked, so we keep requesting the shortfall until the
     * count is reached. Tolerates a few transient empty responses before giving up,
     * with a hard call cap so a persistently-refusing model can't loop forever.
     * Excess is trimmed. Runs on the worker thread.
     */
    private List<GeneratedPayload> fillToCount(IntFunction<List<GeneratedPayload>> gen, int count) {
        List<GeneratedPayload> out = new ArrayList<>();
        int emptyStreak = 0;
        int calls = 0;
        int maxCalls = count * 2 + 5;
        while (out.size() < count && calls++ < maxCalls && !cancelRequested) {
            List<GeneratedPayload> got = gen.apply(count - out.size());
            if (got == null || got.isEmpty()) {
                if (++emptyStreak >= 3) break; // model keeps returning nothing (likely refusing)
                continue;
            }
            emptyStreak = 0;
            out.addAll(got);
        }
        return out.size() > count ? new ArrayList<>(out.subList(0, count)) : out;
    }

    /** Custom goal field wins when non-empty; otherwise the dropdown selection. */
    private Taxonomy.Intent effectiveGoal() {
        String custom = customGoalField.getText().trim();
        if (!custom.isEmpty()) {
            return new Taxonomy.Intent("custom", "custom", custom,
                    "response fulfills the stated goal (returns the requested data / performs the requested action)");
        }
        IntentItem item = (IntentItem) intentCombo.getSelectedItem();
        return item == null ? null : item.intent;
    }

    private void selectIntent(String id) {
        for (int i = 0; i < intentCombo.getItemCount(); i++) {
            if (intentCombo.getItemAt(i).intent.id.equals(id)) { intentCombo.setSelectedIndex(i); return; }
        }
    }

    // ---- Auto-generate (preset level campaigns) ----

    /** A unit of auto work: generate `count` base payloads for these technique(s) in `language`,
     *  then emit one row per evasion config (empty config = plaintext). */
    private record Batch(List<Taxonomy.Technique> techniques, boolean combine, int count,
                         String language, List<List<String>> evasionConfigs) {}

    private List<Taxonomy.Technique> allTechniques() {
        return new ArrayList<>(techBoxes.values());
    }

    /** Resolve a stack of technique ids to the live Technique objects, skipping any not present. */
    private List<Taxonomy.Technique> resolveStack(String[] ids) {
        Map<String, Taxonomy.Technique> byId = new java.util.HashMap<>();
        for (Taxonomy.Technique t : techBoxes.values()) byId.put(t.id, t);
        List<Taxonomy.Technique> out = new ArrayList<>();
        for (String id : ids) { Taxonomy.Technique t = byId.get(id); if (t != null) out.add(t); }
        return out;
    }

    private List<Batch> level1Batches() {
        // Every technique, plaintext, no chaining.
        return List.of(new Batch(allTechniques(), false, L1_PER_TECH, ENGLISH, List.of(List.of())));
    }

    private List<Batch> level2Batches() {
        // Every technique once, cross-producted with common local encodings.
        return List.of(new Batch(allTechniques(), false, 1, ENGLISH, L2_EVASIONS));
    }

    private List<Batch> level3Batches() {
        // Chained stacks x languages x (single + stacked) encodings. Offline can't translate -> English only.
        boolean offline = offlineBox.isSelected();
        List<String> langs = new ArrayList<>();
        langs.add(ENGLISH);
        if (!offline) for (String l : L3_LANGS) langs.add(l);
        List<Batch> out = new ArrayList<>();
        for (String lang : langs) {
            for (String[] ids : L3_STACKS) {
                List<Taxonomy.Technique> stack = resolveStack(ids);
                if (stack.size() >= 2) out.add(new Batch(stack, true, L3_PER_STACK, lang, L3_EVASIONS));
            }
        }
        return out;
    }

    /** Predict how many payloads a level will produce, given the current Online/Offline setting. */
    private void refreshAutoCounts() {
        int nTech = techBoxes.size();
        int l1 = nTech * L1_PER_TECH;
        int l2 = nTech * L2_EVASIONS.size();
        int nLangs = offlineBox.isSelected() ? 1 : (L3_LANGS.length + 1);
        int l3 = L3_STACKS.length * nLangs * L3_PER_STACK * L3_EVASIONS.size();
        autoL1Btn.setText("Level 1  (~" + l1 + ")");
        autoL2Btn.setText("Level 2  (~" + l2 + ")");
        autoL3Btn.setText("Level 3  (~" + l3 + (offlineBox.isSelected() ? ", English only" : "") + ")");
    }

    /**
     * Run a list of preset batches on a worker thread, streaming rows into the same results table.
     * Pipeline per batch: technique(s) -> language (model-baked, online) -> local encodings (last).
     */
    private void runAuto(String label, List<Batch> batches) {
        Taxonomy.Intent goal = effectiveGoal();
        if (goal == null) { status.setText("Pick a goal (dropdown or custom field) before auto-generating."); return; }
        boolean offline = offlineBox.isSelected();
        String injection = manualInjection != null ? manualInjection : "the user input field";
        LlmClient tmpClient = null;
        if (!offline) {
            if (context == null) { status.setText("Ingest request(s) first, or tick Offline mode, then auto-generate."); return; }
            try {
                tmpClient = clientFactory.get();
            } catch (RuntimeException ex) {
                status.setText(ex.getMessage());
                return;
            }
        }

        setBusy(true);
        final AppContext ctx = context;
        final LlmClient llm = tmpClient;
        final Taxonomy.Intent g = goal;
        final int rowsBefore = resultsModel.getRowCount();
        final int totalBatches = batches.size();

        new SwingWorker<Void, GeneratedPayload>() {
            int done = 0;

            @Override
            protected Void doInBackground() {
                currentWorker = this;
                OfflineGenerator og = offline ? new OfflineGenerator() : null;
                PayloadGenerator gen = offline ? null : new PayloadGenerator(llm);
                for (Batch b : batches) {
                    if (cancelRequested) break;
                    boolean translate = !offline && b.language() != null && !b.language().equals(ENGLISH);
                    List<String> mDesc = new ArrayList<>();
                    List<String> mIds = new ArrayList<>();
                    if (translate) {
                        mDesc.add("Translate the ENTIRE payload into " + b.language()
                                + ", preserving its meaning; output only the " + b.language() + " text.");
                        mIds.add("lang:" + b.language());
                    }

                    // Stage 1: base payloads (language baked in online).
                    List<GeneratedPayload> base = new ArrayList<>();
                    if (offline) {
                        if (b.combine()) base.addAll(og.generateCombined(b.techniques(), g, b.count()));
                        else for (Taxonomy.Technique t : b.techniques()) base.addAll(og.generate(List.of(t), g, b.count()));
                    } else if (b.combine()) {
                        base.addAll(fillToCount(n -> gen.generateCombined(ctx, b.techniques(), g, injection, n, mDesc, mIds), b.count()));
                    } else {
                        for (Taxonomy.Technique t : b.techniques()) {
                            if (cancelRequested) break;
                            base.addAll(fillToCount(n -> gen.generate(ctx, List.of(t), g, injection, n, mDesc, mIds), b.count()));
                        }
                    }

                    // Stage 2: apply each local encoding config (last), labelling language + encoding.
                    String langTag = translate ? b.language() + " + " : "";
                    for (List<String> cfg : b.evasionConfigs()) {
                        String enc = cfg.isEmpty() ? "plaintext" : String.join("+", cfg);
                        String evLabel = langTag + enc;
                        for (GeneratedPayload bp : base) {
                            String text = bp.payload;
                            for (String id : cfg) text = Evasions.apply(id, text);
                            publish(new GeneratedPayload(bp.technique, bp.intent, text, bp.successIndicator, evLabel));
                        }
                    }
                    done++;
                    setProgress(Math.min(100, done * 100 / Math.max(1, totalBatches)));
                }
                return null;
            }

            @Override
            protected void process(List<GeneratedPayload> chunk) {
                for (GeneratedPayload p : chunk) {
                    resultsModel.addRow(new Object[]{p.technique, p.intent, p.evasion, p.payload});
                }
                status.setText(label + "... " + resultsModel.getRowCount() + " payload(s) so far ("
                        + done + "/" + totalBatches + " batches).");
            }

            @Override
            protected void done() {
                setBusy(false);
                currentWorker = null;
                if (cancelRequested || isCancelled()) {
                    int kept = resultsModel.getRowCount() - rowsBefore;
                    status.setText(label + " cancelled after " + done + "/" + totalBatches + " batches. Kept "
                            + kept + " payload(s) (" + resultsModel.getRowCount() + " total).");
                    return;
                }
                try {
                    get();
                    int added = resultsModel.getRowCount() - rowsBefore;
                    if (added == 0) {
                        status.setText(label + " produced 0 payloads. The model may have refused, or the response was "
                                + "truncated. Try Offline mode, or check Extensions > Installed > PromptForge > Errors.");
                    } else {
                        status.setText("Done. " + label + " added " + added + " payload(s) ("
                                + resultsModel.getRowCount() + " total). Export for Intruder.");
                    }
                } catch (Exception ex) {
                    api.logging().logToError("PromptForge auto-generate failed: " + ex);
                    status.setText(label + " failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private static final class IntentItem {
        final Taxonomy.Intent intent;
        IntentItem(Taxonomy.Intent intent) { this.intent = intent; }
        @Override public String toString() { return intent.id + " - " + intent.goal; }
    }
}
