# How PromptForge uses AI (and your API key)

This document is for anyone who wants to know **exactly what PromptForge does with the API key they paste in, what data leaves their machine, and where every AI call lives in the source.** Nothing here is hidden or obfuscated - every claim below points at a specific file and line you can read yourself.

**The short version:**

- Your API key is typed into an in-memory password field. It is **never written to disk, never saved in Burp's project/preferences, and never logged.** It is used only to authenticate requests to the provider *you* configured (Anthropic, or an OpenAI-compatible endpoint you point at).
- The only network calls PromptForge makes are the LLM completion requests you trigger (Test connection, Generate, or an Auto-generate level). There is no telemetry, analytics, "phone home," update check, or third-party call of any kind.
- **Offline mode makes zero network calls** and needs no key at all.
- PromptForge never runs the generated payloads against your target. It only *creates* text; you fire it manually through Burp.

All paths below are under `extension/src/main/java/sec/promptforge/`.

---

## 1. Where the API key lives, and where it does not

The key is a Swing `JPasswordField` on the toolbar:

- Declared: [`PromptForgeTab.java:27`](extension/src/main/java/sec/promptforge/PromptForgeTab.java#L27) - `private final JPasswordField apiKeyField = ...`
- Read (the **only** place): [`PromptForgeTab.java:245`](extension/src/main/java/sec/promptforge/PromptForgeTab.java#L245) - inside `buildClient()`:

```java
String key = new String(apiKeyField.getPassword()).trim();
```

That value is handed directly to a client constructor and nowhere else:

- Anthropic: `new AnthropicClientWrapper(key, model)` ([`PromptForgeTab.java`](extension/src/main/java/sec/promptforge/PromptForgeTab.java), `buildClient()`)
- OpenAI-compatible: `new OpenAiCompatibleClient(baseUrl, key, model)`

**What does NOT happen to the key** (you can verify by grep):

- It is **not** persisted. PromptForge calls no Burp persistence/preferences API - the key is not in your Burp project file or config. Close Burp and it is gone.
- It is **not** logged. The only `logToError`/`logToOutput` calls log the extension lifecycle and exception messages, never the key (see [`PromptForgeExtension.java`](extension/src/main/java/sec/promptforge/PromptForgeExtension.java) and the three `logToError` sites in `SessionPanel.java`).
- It is **not** sent anywhere except as the auth header of the request to the provider you chose.

A fresh client is built per generation run via a `Supplier<LlmClient>` (`clientFactory`), so the key is read at call time from the field and not cached in a long-lived object.

---

## 2. The AI abstraction: one tiny interface, two implementations

Every AI call in the codebase goes through a single method:

- [`LlmClient.java`](extension/src/main/java/sec/promptforge/LlmClient.java) - the whole interface is one method:

```java
public interface LlmClient {
    String complete(String systemPrompt, String userPrompt);
}
```

There are exactly two implementations, and they are the only code that opens a network connection:

### Anthropic (native SDK) - [`AnthropicClientWrapper.java`](extension/src/main/java/sec/promptforge/AnthropicClientWrapper.java)

- The key is passed to the official Anthropic SDK builder at [`AnthropicClientWrapper.java:28-30`](extension/src/main/java/sec/promptforge/AnthropicClientWrapper.java#L28).
- `complete()` ([line 34](extension/src/main/java/sec/promptforge/AnthropicClientWrapper.java#L34)) builds a `MessageCreateParams` (system prompt + one user message), **streams** the response from `api.anthropic.com`, and concatenates the text deltas. That is the entire request; there are no extra headers or side calls.

### OpenAI-compatible - [`OpenAiCompatibleClient.java`](extension/src/main/java/sec/promptforge/OpenAiCompatibleClient.java)

- Uses the plain JDK `HttpClient` (no SDK). `complete()` ([line 44](extension/src/main/java/sec/promptforge/OpenAiCompatibleClient.java#L44)) POSTs a standard `{model, messages:[system,user]}` body to `{baseUrl}/chat/completions`.
- The key is attached **only** as `Authorization: Bearer ...` ([lines 56-57](extension/src/main/java/sec/promptforge/OpenAiCompatibleClient.java#L56)), and only if non-empty (local runners like Ollama often need no key).
- The **Base URL is yours** - point it at OpenAI, a localhost model server, or an aggregator. Nothing is hardcoded except the default `https://api.openai.com/v1`.

If you want to audit "does PromptForge ever talk to the network?", these two files are the complete answer.

---

## 3. Test connection - what it actually sends

The green/red key indicator is not magic; it fires one trivial completion.

- [`PromptForgeTab.java:193`](extension/src/main/java/sec/promptforge/PromptForgeTab.java#L193) - `onTest()` builds the client and, on a background thread, calls:

```java
String r = c.complete("You are a connectivity check.", "Reply with the single word: OK");
```

([`PromptForgeTab.java:212`](extension/src/main/java/sec/promptforge/PromptForgeTab.java#L212).) If a non-null string comes back, the key field border turns green; otherwise red with the provider's error message. This validates the whole config (key + base URL + model) with a request small enough to cost effectively nothing. **No target data is involved in Test connection** - just those two fixed strings.

---

## 4. How payload generation works, and what data is sent to the model

All prompt construction lives in [`PayloadGenerator.java`](extension/src/main/java/sec/promptforge/PayloadGenerator.java). Two entry points:

- `generate(...)` ([line 88](extension/src/main/java/sec/promptforge/PayloadGenerator.java#L88)) - N payloads per technique, one API call per technique.
- `generateCombined(...)` ([line 119](extension/src/main/java/sec/promptforge/PayloadGenerator.java#L119)) - a two-stage "combine": generate each technique separately, then one merge call weaves them into a single stacked payload, grounded in those texts so the model cannot drift outside the chosen techniques.

**The system prompt** is a fixed constant at [`PayloadGenerator.java:25`](extension/src/main/java/sec/promptforge/PayloadGenerator.java#L25) (`SYSTEM_PROMPT`). It instructs the model to act as an authorized-pentest payload engineer, output *only* the injection value (no HTTP wrapper), apply *only* the named technique(s), and return JSON. You can read it in full there.

**What goes into the user prompt** is assembled by `appendContext(...)` ([line 168](extension/src/main/java/sec/promptforge/PayloadGenerator.java#L168)). For **online** generation this includes:

- the target endpoint and the injection field name,
- the **distilled vocabulary profile** (field names, ID syntax, domain terms) learned from the requests you ingested,
- one sample benign request and response shape,
- the goal (taxonomy intent or your custom goal),
- the technique's taxonomy description and a few canonical examples (grounding).

**Be aware:** online generation therefore sends a distilled summary of your captured traffic to the LLM provider you configured. That is by design - it is how payloads get re-skinned in the app's language - but it means you should only ingest traffic you are comfortable sending to that provider. If that is a concern, use **Offline mode**, which builds payloads locally from the bundled templates and sends nothing anywhere.

The model's raw text is parsed object-by-object (tolerant of truncation) in `parse(...)`, so a cut-off response still yields every complete payload.

---

## 5. Auto-generate levels and the batch engine

The one-click **Level 1 / 2 / 3** buttons do not each have bespoke logic. Each level compiles into a **list of `Batch` records**, and one generic engine runs any list.

- The record: [`SessionPanel.java:742`](extension/src/main/java/sec/promptforge/SessionPanel.java#L742)

```java
record Batch(List<Taxonomy.Technique> techniques, boolean combine, int count,
             String language, List<List<String>> evasionConfigs) {}
```

A batch means: *generate `count` base payloads for these technique(s) in this language, then emit one table row per evasion config* (empty config = plaintext). The **expensive model work happens once per batch**; the local encoders then fan out cheaply over those base payloads. So a batch that costs ~7 API calls can produce `count x (number of encodings)` rows.

- The level definitions are just data: `level1Batches()`, `level2Batches()`, and `level3Batches()` ([`SessionPanel.java:768`](extension/src/main/java/sec/promptforge/SessionPanel.java#L768)).
- **Level 3 is a matrix.** It builds **one batch per (language x technique-stack)**. With ~10 curated multi-technique stacks and ~7 languages that is ~70 batches; each stack is combined and each base payload is emitted across single and stacked encodings. The languages are deliberately weighted toward **low-resource** languages (Hawaiian, Zulu, Scots Gaelic, Hmong) because safety alignment is weaker there - see the `L3_STACKS` and `L3_LANGS` constants near the top of `SessionPanel.java`.

The runner: [`SessionPanel.java:800`](extension/src/main/java/sec/promptforge/SessionPanel.java#L800) - `runAuto(label, batches)` executes the list on a `SwingWorker` background thread and `publish()`es rows into the table as each batch finishes, so results stream in and progress is a simple `done / totalBatches` counter. Predicted counts shown on the buttons are computed in `refreshAutoCounts()` ([line 785](extension/src/main/java/sec/promptforge/SessionPanel.java#L785)) directly from these constants, so the label always matches what will actually be produced.

Every AI call inside a batch still goes through the same `PayloadGenerator` -> `LlmClient.complete(...)` path from sections 2 and 4. Auto-generate introduces **no new network path** - it just orchestrates the same calls.

---

## 6. How Cancel works

Long runs (especially Level 3) are cancellable, and cancellation is **non-destructive** - payloads already generated stay in the table.

Two mechanisms work together:

1. **A `cancelRequested` flag** ([`SessionPanel.java:110`](extension/src/main/java/sec/promptforge/SessionPanel.java#L110), `volatile`). It is checked between batches ([line 832](extension/src/main/java/sec/promptforge/SessionPanel.java#L832)), between per-technique calls (lines 517, 532, 851), and inside the top-up loop `fillToCount` ([line 709](extension/src/main/java/sec/promptforge/SessionPanel.java#L709)). This is the reliable path: it stops PromptForge from launching any **new** model calls almost immediately.
2. **Worker interrupt** - the Cancel button also calls `currentWorker.cancel(true)` ([the handler at line 377](extension/src/main/java/sec/promptforge/SessionPanel.java#L377)) as a best-effort attempt to abort the request already in flight. Each run registers itself as `currentWorker` at the start of `doInBackground()` (lines 509 and 828).

Honest caveat: because the flag is checked *between* calls, the single request already in flight when you click Cancel may finish before the run stops - at most one more call, never the rest of the campaign. The status bar then reports how many payloads were kept (e.g. *"Level 3 cancelled after 12/70 batches. Kept 216 payload(s)."*).

---

## 7. Verify it yourself

Two greps summarize the trust story:

```bash
cd extension/src/main/java/sec/promptforge

# Every place the API key is read (should be exactly one: buildClient):
grep -rn "getPassword" .

# Every network-capable call site (SDK stream + JDK HttpClient send):
grep -rn "createStreaming\|HTTP.send\|logToOutput\|logToError" .
```

You will find the key read in one place, the two client implementations from section 2, and only lifecycle/exception logging - no persistence, no telemetry, no third-party endpoints.
