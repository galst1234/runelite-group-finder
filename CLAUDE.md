# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Run plugin in dev mode (launches RuneLite with plugin loaded)
gradle run

# Run unit tests
gradle test

# Build distributable JAR
gradle shadowJar

# Compile only
gradle compileJava
```

## Architecture

RuneLite client plugin for finding/creating groups for raids, bosses, and minigames. Communicates with a REST backend (default: `http://localhost:8000`).

**Threading rules:**
- Game state / client API access → `clientThread.invokeLater()`
- UI updates → `SwingUtilities.invokeLater()`
- Background work (API calls, polling) → `executorService.execute()`

**Player name handling:** RuneLite encodes spaces as `\u00A0` (non-breaking space). Normalize with `.replace('\u00A0', ' ')` before comparisons or API calls.

**`Activity` enum:** `toString()` returns the human-readable `displayName`, not the constant name. The constant name is what gets sent as the API query param.

**`GroupFinderClient` error handling:** Returns safe defaults on HTTP errors or network failures (empty list, null, false). Malformed JSON throws `RuntimeException`.

## Testing

Framework: JUnit 5 + Mockito + AssertJ + OkHttp3 MockWebServer.

**`GroupFinderPluginBehaviorTest`** — Uses reflection to inject mocked dependencies (RuneLite plugins don't have constructor injection). Executor and `clientThread` stubs run callbacks inline for synchronous assertions. Mockito strictness is `LENIENT` so shared `@BeforeEach` stubs don't fail tests that don't exercise them.

**`GroupFinderClientTest`** — Uses `MockWebServer` for real HTTP round-trips. Tests cover happy path, HTTP errors, network failures, and malformed JSON for each endpoint.

**`GroupListingFixture`** — Provides a pre-configured `GroupListing` for tests via `GroupListingFixture.listing()`.

## Plugin Hub Notes

- License: BSD 2-Clause (required by Plugin Hub)
- Plugin properties: `runelite-plugin.properties`
