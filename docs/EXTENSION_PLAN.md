> **Historical design note.** This is the *original pre-build plan*, kept for provenance. It does **not** describe the shipped extension - several ideas here were built differently or deliberately dropped (e.g. injection points are now marked **manually**, not auto-detected; there is no success-indicator column, refine loop, or OWASP report). For how PromptForge actually works today, see **[../README.md](../README.md)** and **[../AI_TRANSPARENCY.md](../AI_TRANSPARENCY.md)**.

---

# PromptForge Burp Extension - Architecture (Phase 2)

Montoya API extension that turns the static pack into **app-native** payloads by
learning the target's vocabulary from real traffic.

Decisions locked: **Java/Kotlin**, **external LLM API (default Claude)**, static
pack ships first and feeds the extension as its template library.

## Pipeline

```
   ┌─ ingest ─────────────┐   ┌─ context extract ──┐   ┌─ generate ─────────┐   ┌─ execute ───────────┐
   │ right-click HTTP hist │   │ injection points    │   │ technique templates │   │ push to Intruder/    │
   │ (ContextMenuItems-    │ -> │ (JSON fields,       │ -> │ + app vocab + Claude│ -> │ Repeater             │
   │  Provider)            │   │  messages[] arrays) │   │ -> app-native payload│   │ + light refine loop  │
   │ OR upload .json/.har  │   │ domain vocab/entities│   │ per technique       │   │ + taxonomy/OWASP rpt │
   └──────────────────────┘   └────────────────────┘   └─────────────────────┘   └─────────────────────┘
```

## Components

### 1. Ingest
- `ContextMenuItemsProvider` -> "Send to PromptForge" from Proxy history / any HttpRequestResponse.
- File upload tab accepting Burp-exported items (`.json`), `.har`, or raw request dumps.

### 2. Context extractor
- **Injection-point detection:** walk JSON bodies; flag string fields and OpenAI-style
  `messages[]` arrays as candidate insertion points (reuse LLMInjector's approach as prior art).
- **Vocabulary/entity mining:** from benign requests/responses, extract domain terms, ID
  syntax (e.g. `patient id: 24345`), command verbs, field names, brand/persona. This is the
  context blob handed to the LLM.
- **Endpoint/auth shape:** method, content-type, auth headers (for replay, never logged).

### 3. Generator
- For each technique in `data/techniques.json`, send `{technique template + why + extracted
  context}` to the LLM with an instruction: *re-skin this technique using the app's own
  vocabulary; keep the technique mechanism intact.*
- Default model **claude-opus-4-8** (quality) / **claude-sonnet-4-6** (speed); BYO key.
  Pluggable provider interface so OpenAI/Ollama can drop in later.
- Optionally pass generated text through `lib/encoders.py` equivalents for evasion variants.

### 4. Execute
- Send via Burp HTTP; or "push to Intruder/Repeater" for manual work (recommended default).
- **Light refine loop** (1-few turns): fire -> read response -> ask LLM for one adapted
  follow-up. NOT a full agent.
- **Defer heavy multi-turn to PyRIT** via export - do not reimplement PyRIT's Red Teaming
  Orchestrator. (PyRIT-Ship already bridges Burp↔PyRIT.)
- Response analysis: match each payload's `success_indicator`; response diffing vs. a benign
  baseline; flag potential breaks.

### 5. Report
- Map each finding to taxonomy node (intent/technique/evasion) + OWASP LLM Top 10.

## Open questions to resolve before Phase 2

- Inline campaign runner vs. generate-and-handoff-only as the default execution mode
  (the AI-vs-target idea overlaps PyRIT - leaning: light refine loop in-extension, scale via PyRIT).
- Whether to embed evasion encoding in the extension or call the Python lib as a sidecar.
- Secrets handling for the LLM API key (Burp project file vs. env var vs. settings).

## Build skeleton (when Phase 2 starts)

- Gradle + Kotlin, `montoya-api` dependency.
- `BurpExtension.initialize()` registers context menu + a suite tab.
- Bundle `taxonomy.json` + `techniques.json` as resources so the extension and pack stay in sync.
