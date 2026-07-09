# PromptForge - Burp extension

This directory is the Burp Suite extension (Montoya API + Anthropic Java SDK / OpenAI-compatible).

**Documentation lives in the project root, which is the single source of truth:**

- **[../README.md](../README.md)** - what PromptForge is, the full feature reference, quick start, and the Gandalf worked example.
- **[../AI_TRANSPARENCY.md](../AI_TRANSPARENCY.md)** - how the extension uses AI and your API key, with file-and-line pointers (clients, generation, the Level 3 batch engine, cancel).

## Build

Requires JDK 17+. The Gradle wrapper is pinned and checked in, so you do not need Gradle installed:

```bash
./gradlew shadowJar
# -> build/libs/promptforge-0.1.0.jar   (a single self-contained, shaded jar)
```

A prebuilt `promptforge-0.1.0.jar` is also attached to the latest GitHub Release for convenience.

## Load in Burp

Extensions -> Installed -> Add -> Extension type: Java -> select the jar. A "PromptForge" tab appears. Configure the provider and paste your API key in the tab (see the root README for the full workflow).

> Burp does not hot-reload. After rebuilding, untick and re-tick the extension (or remove and re-add the jar) to load the new version.

## Source layout

Every AI call goes through `LlmClient` (implemented by `AnthropicClientWrapper` and `OpenAiCompatibleClient`) - the only network-touching code. See the Architecture section of the root README and AI_TRANSPARENCY.md for the full class map.
