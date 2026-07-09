package sec.promptforge;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Top-level PromptForge tab = a session manager. Holds the shared provider
 * settings (provider, API key, model, base URL) and a set of sessions
 * (Intruder-style tabs). "Send to PromptForge" opens a new session; "New
 * session" opens a fresh one. The configured provider is built on demand via
 * {@link #buildClient()} and handed to each session.
 */
public final class PromptForgeTab extends JPanel {

    private static final String ANTHROPIC = "Anthropic (native)";
    private static final String OPENAI_COMPAT = "OpenAI-compatible";
    private static final String[] CLAUDE_MODELS = {"claude-opus-4-8", "claude-sonnet-4-6", "claude-fable-5"};
    private static final String[] OPENAI_MODELS = {"gpt-4o", "gpt-4o-mini"};

    private final MontoyaApi api;
    private final Taxonomy taxonomy;
    private final JComboBox<String> providerCombo = new JComboBox<>(new String[]{ANTHROPIC, OPENAI_COMPAT});
    private final JPasswordField apiKeyField = new JPasswordField(36);
    // Default border captured so we can restore it (theme-safe; never recolor the field background).
    private final javax.swing.border.Border defaultKeyBorder = apiKeyField.getBorder();
    private static final Color OK_GREEN = new Color(60, 170, 90);
    private static final Color BAD_RED = new Color(220, 80, 80);
    private final JComboBox<String> modelCombo = new JComboBox<>(CLAUDE_MODELS);
    private final JTextField baseUrlField = new JTextField("https://api.openai.com/v1", 30);
    private final JButton testBtn = new JButton("Test connection");
    private final JLabel connStatus = new JLabel(" ");
    private final JTabbedPane sessions = new JTabbedPane();
    private int sessionCounter = 0;

    public PromptForgeTab(MontoyaApi api, Taxonomy taxonomy) {
        this.api = api;
        this.taxonomy = taxonomy;
        setLayout(new BorderLayout(8, 8));

        modelCombo.setEditable(true); // let users type any model id
        baseUrlField.setEnabled(false);
        baseUrlField.setToolTipText("OpenAI: https://api.openai.com/v1   Ollama: http://localhost:11434/v1   "
                + "OpenRouter: https://openrouter.ai/api/v1");
        providerCombo.addActionListener(e -> onProviderChange());

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("Provider:"));
        row1.add(providerCombo);
        row1.add(new JLabel("  API key:"));
        row1.add(apiKeyField);
        row1.add(new JLabel("  Model:"));
        row1.add(modelCombo);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(new JLabel("Base URL (OpenAI-compatible):"));
        row2.add(baseUrlField);
        testBtn.addActionListener(e -> onTest());
        row2.add(testBtn);
        row2.add(connStatus);
        JButton newSession = new JButton("New session");
        newSession.addActionListener(e -> addSession());
        row2.add(newSession);

        JPanel toolbar = new JPanel();
        toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.Y_AXIS));
        toolbar.setBorder(BorderFactory.createTitledBorder("Provider & connection"));
        toolbar.add(row1);
        toolbar.add(row2);

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(toolbar, BorderLayout.NORTH);

        // Sessions and a browsable technique reference live side by side as top-level tabs.
        JTabbedPane center = new JTabbedPane();
        center.addTab("Sessions", sessions);
        center.addTab("Technique reference", buildReference("techniques", techniqueRefs()));
        center.addTab("Evasion reference", buildReference("evasions", evasionRefs()));
        add(center, BorderLayout.CENTER);
        addSession(); // start with one
    }

    /** A uniform reference entry: Name / What it is / Why it works / Examples. */
    private record Ref(String id, String name, String what, String why, List<String> examples) {}

    private List<Ref> techniqueRefs() {
        List<Ref> out = new java.util.ArrayList<>();
        for (Taxonomy.Technique t : taxonomy.techniques()) out.add(new Ref(t.id, t.name, t.what, t.why, t.examples));
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private List<Ref> evasionRefs() {
        List<Ref> out = new java.util.ArrayList<>();
        for (Taxonomy.Evasion e : taxonomy.evasions()) out.add(new Ref(e.id, e.name, e.what, e.why, e.examples));
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** Searchable reference: list on the left, "what + why it works" + examples on the right. */
    private JComponent buildReference(String noun, List<Ref> items) {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JTextField filter = new JTextField();
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.add(new JLabel("Search " + noun + ":"), BorderLayout.WEST);
        top.add(filter, BorderLayout.CENTER);
        panel.add(top, BorderLayout.NORTH);

        DefaultListModel<Ref> model = new DefaultListModel<>();
        for (Ref r : items) model.addElement(r);
        JList<Ref> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((lst, val, idx, sel, foc) -> {
            JLabel l = new JLabel(val.name());
            l.setOpaque(true);
            l.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
            if (sel) { l.setBackground(lst.getSelectionBackground()); l.setForeground(lst.getSelectionForeground()); }
            return l;
        });
        JScrollPane listScroll = new JScrollPane(list);

        JEditorPane detail = new JEditorPane("text/html", "");
        detail.setEditable(false);
        JScrollPane detailScroll = new JScrollPane(detail);

        list.addListSelectionListener(e -> {
            Ref r = list.getSelectedValue();
            if (r != null) { detail.setText(referenceHtml(r)); detail.setCaretPosition(0); }
        });

        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void refresh() {
                String q = filter.getText().trim().toLowerCase();
                model.clear();
                for (Ref r : items) {
                    if (q.isEmpty() || r.name().toLowerCase().contains(q)
                            || r.why().toLowerCase().contains(q) || r.what().toLowerCase().contains(q)) {
                        model.addElement(r);
                    }
                }
                if (!model.isEmpty()) list.setSelectedIndex(0);
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refresh(); }
        });

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailScroll);
        split.setResizeWeight(0.28);
        panel.add(split, BorderLayout.CENTER);
        if (!model.isEmpty()) list.setSelectedIndex(0);
        return panel;
    }

    private String referenceHtml(Ref r) {
        StringBuilder ex = new StringBuilder();
        for (String e : r.examples()) ex.append("<li style='margin-bottom:4px;'>").append(esc(e)).append("</li>");
        return "<html><body style='font-family:sans-serif; margin:8px;'>"
                + "<h2 style='margin:0 0 2px 0;'>" + esc(r.name()) + "</h2>"
                + "<div style='color:#888; font-size:9px; margin-bottom:12px;'>" + esc(r.id()) + "</div>"
                + "<b>What it is</b>"
                + "<p style='margin:4px 0 12px 0;'>" + esc(r.what()) + "</p>"
                + "<b>Why it works</b>"
                + "<p style='margin:4px 0 12px 0;'>" + esc(r.why()) + "</p>"
                + (ex.length() == 0 ? "" : "<b>Examples</b><ul style='margin:4px 0;'>" + ex + "</ul>")
                + "</body></html>";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void onProviderChange() {
        boolean oai = OPENAI_COMPAT.equals(providerCombo.getSelectedItem());
        baseUrlField.setEnabled(oai);
        modelCombo.removeAllItems();
        for (String m : (oai ? OPENAI_MODELS : CLAUDE_MODELS)) modelCombo.addItem(m);
        resetKeyIndicator(); // config changed - clear stale validity signal
    }

    private void resetKeyIndicator() {
        apiKeyField.setBorder(defaultKeyBorder);
        connStatus.setForeground(null); // inherit theme default
        connStatus.setText(" ");
    }

    /** Fire a tiny prompt through the configured provider; green = works, red = failed. */
    private void onTest() {
        LlmClient client;
        try {
            client = buildClient();
        } catch (RuntimeException ex) {
            setConnStatus(false, ex.getMessage());
            return;
        }
        testBtn.setEnabled(false);
        apiKeyField.setBorder(defaultKeyBorder);
        connStatus.setForeground(null);
        connStatus.setText("testing...");
        final LlmClient c = client;
        new SwingWorker<Boolean, Void>() {
            String err;

            @Override
            protected Boolean doInBackground() {
                try {
                    String r = c.complete("You are a connectivity check.", "Reply with the single word: OK");
                    return r != null;
                } catch (Exception ex) {
                    err = ex.getMessage();
                    return false;
                }
            }

            @Override
            protected void done() {
                testBtn.setEnabled(true);
                try {
                    if (get()) setConnStatus(true, "connection OK");
                    else setConnStatus(false, err == null ? "failed" : err);
                } catch (Exception ex) {
                    setConnStatus(false, ex.getMessage());
                }
            }
        }.execute();
    }

    private void setConnStatus(boolean ok, String msg) {
        // Colored border + colored status text (works in light and dark themes;
        // never sets the field background, which would clash with dark mode).
        apiKeyField.setBorder(BorderFactory.createLineBorder(ok ? OK_GREEN : BAD_RED, 2));
        connStatus.setForeground(ok ? OK_GREEN : BAD_RED);
        String m = msg == null ? "" : msg;
        if (m.length() > 80) m = m.substring(0, 80) + "...";
        connStatus.setText(ok ? "valid - " + m : "invalid - " + m);
    }

    /** Build the configured provider client. Throws (with a UI-friendly message) if misconfigured. */
    private LlmClient buildClient() {
        String key = new String(apiKeyField.getPassword()).trim();
        String model = String.valueOf(modelCombo.getEditor().getItem()).trim();
        if (OPENAI_COMPAT.equals(providerCombo.getSelectedItem())) {
            return new OpenAiCompatibleClient(baseUrlField.getText().trim(), key, model);
        }
        if (key.isEmpty()) {
            throw new IllegalStateException("Enter your Anthropic API key, or tick Offline mode.");
        }
        return new AnthropicClientWrapper(key, model);
    }

    private SessionPanel addSession() {
        SessionPanel panel = new SessionPanel(api, taxonomy, this::buildClient);
        sessions.addTab("Session " + (++sessionCounter), panel);
        sessions.setSelectedComponent(panel);
        return panel;
    }

    /** Context-menu entry: always open a NEW session so an existing one is never overwritten. */
    public void ingestSelected(List<HttpRequestResponse> selected) {
        SwingUtilities.invokeLater(() -> addSession().ingestSelected(selected));
    }
}
