---
name: write-tests
description: Write or extend tests for this RuneLite plugin, following the project's testing conventions (JUnit 5, Mockito, AssertJ, MockWebServer). Use when asked to add tests, cover a method, or improve test coverage.
argument-hint: [class or method to test]
allowed-tools: Read, Grep, Glob, Edit, Write, Bash
---

Write tests for $ARGUMENTS following the guide below.

---

## Toolchain

| Tool | Role |
|---|---|
| JUnit 5 | Test runner and lifecycle |
| Mockito + `MockitoExtension` | Mocking/stubbing dependencies |
| AssertJ | Fluent, readable assertions |
| OkHttp `MockWebServer` | Real HTTP round-trips against a local server |

Do not use PowerMock, JMockit, or any bytecode-rewriting framework.
If code is hard to test, refactor it (extract logic, inject the dependency).

### build.gradle test classpath rules

When adding test dependencies, audit every `compileOnly` dependency in the
main configuration. If any production class that will be loaded during tests
references that dependency (including via generated code such as Lombok
annotations), you MUST add it to `testRuntimeOnly` as well.

Required entries whenever Lombok is present on the main compile classpath:

    testRuntimeOnly 'org.slf4j:slf4j-api:<version>'
    testRuntimeOnly 'org.slf4j:slf4j-simple:<version>'

Required entries whenever javax.inject / Jakarta inject is compileOnly:

    testRuntimeOnly 'javax.inject:javax.inject:1'

This project uses Gradle exclusively. Do NOT create a pom.xml or a target/
directory. Any Maven artifacts are spurious and must be deleted.

---

## Test structure and naming

### File layout

```
src/test/java/com/groupfinder/
  unit/                               Pure logic — no RuneLite, no network
  GroupFinderPluginBehaviorTest.java  Plugin behavior — same package as plugin (see note)
  client/                             HTTP client contract — MockWebServer
```

**Plugin behavior tests must be in `package com.groupfinder`, not a sub-package.**
`GroupFinderPlugin`'s callable methods (`createGroup`, `deleteGroup`, `refreshListings`, etc.)
are package-private. Java package-private means accessible only within `com.groupfinder` —
a class in `com.groupfinder.plugin` cannot call them. Put the test file directly in
`src/test/java/com/groupfinder/` with `package com.groupfinder;` at the top.

`GroupFinderClientTest` lives in `client/` because `GroupFinderClient`'s methods are `public`.

### Class naming

| Subject | Test class name |
|---|---|
| `GroupFinderClient` | `GroupFinderClientTest` |
| `GroupFinderPlugin` | `GroupFinderPluginBehaviorTest` |
| `Activity` | `ActivityTest` |

### Method naming

Use the pattern `methodName_condition_expectedOutcome`:

```java
createGroup_whenNotLoggedIn_showsError()
getGroups_withActivityFilter_appendsQueryParam()
onGameStateChanged_hopping_clearsInFriendsChat()
```

Avoid vague names like `testCreate()`, `happyPath()`, or `works()`.

### @Nested classes

Group related tests with `@Nested` to reduce repetition and improve readability:

```java
class GroupFinderPluginBehaviorTest {

    @Nested class CreateGroup {
        @Test void whenNotLoggedIn_showsError() { ... }
        @Test void whenNoFriendsChat_showsFcError() { ... }
    }

    @Nested class OnGameStateChanged {
        @Test void loginScreen_clearsInFriendsChat() { ... }
    }
}
```

---

## The three test levels

### Level 1 — Pure unit tests (fastest, most valuable)

For classes with no RuneLite dependency: pure Java logic, formatting, parsing, filtering.
No mocks needed. Instantiate directly. Assert outputs.

```java
@Test
void normalizeName_replacesNbspWithSpace() {
    assertEquals("Bob Smith", normalizeName("Bob\u00A0Smith"));
}
```

### Level 2 — Plugin behavior tests (mocked RuneLite)

For `GroupFinderPlugin`: mock all `@Inject`ed dependencies, inject them manually,
then call the method under test directly and assert on outcomes.

**Do not call `startUp()`** — it requires a Guice injector and loads image resources.
Instead inject all fields via reflection in `@BeforeEach`.

### Level 3 — HTTP client contract tests (MockWebServer)

For `GroupFinderClient`: use OkHttp's `MockWebServer` for real HTTP round-trips.
Verify HTTP verb, path, query parameters, request body serialization, and response parsing.
No plugin or RuneLite APIs involved.

---

## Test generation workflow

When generating tests, follow this sequence every time:

1. **Map behavior** — read the method/class under test; list every observable outcome (return value, side effect, thrown exception, mock call)
2. **List cases** — for each outcome, enumerate: happy path, guard/validation failures, boundary inputs, async execution paths, error/exception paths
3. **Write tests** — one test per behavioral claim; use the naming pattern, AAA structure, and the level appropriate for the subject
4. **Run** — compile and execute; a test that cannot be compiled or run is not done
5. **Refine** — if a test passes trivially (asserting something that can never be false) or fails for the wrong reason, fix it
6. **Report gaps** — after writing, list any behaviors that are observable but untestable without refactoring (e.g., private collaborators with no seam); do not silently skip them

---

## RuneLite-specific patterns

### Instantiating the plugin without Guice

```java
private GroupFinderPlugin plugin;

@BeforeEach
void setUp() throws Exception {
    plugin = new GroupFinderPlugin();
    inject("client",              mockClient);
    inject("clientThread",        mockClientThread);
    inject("config",              mockConfig);
    inject("groupFinderClient",   mockGroupFinderClient);
    inject("chatMessageManager",  mockChatMessageManager);
    inject("panel",               mockPanel);        // interface, not Swing class
    inject("executorService",     mockExecutor);
}

private void inject(String field, Object value) throws Exception {
    Field f = GroupFinderPlugin.class.getDeclaredField(field);
    f.setAccessible(true);
    f.set(plugin, value);
}
```

Only inject fields that the test actually touches. Leave the rest null.

**Important:** The declared type of the field in the plugin class determines
the required mock type. A field declared as `ScheduledExecutorService` requires
`@Mock ScheduledExecutorService`, NOT `@Mock ExecutorService`. Check the
actual field type in the plugin source before writing your mock declaration.
Mismatched types cause `IllegalArgumentException` at `field.set(plugin, value)`.

### Mocking the RuneLite API surface

Always mock these — never use real instances:

- `Client` — game state, player, widgets, game state
- `ClientThread` — game-thread dispatcher
- `ChatMessageManager` — in-game message queuing
- `GroupFinderConfig` — plugin configuration values
- `FriendsChatManager` — FC membership and owner
- `Player` — local player name

Use the interface or interface-backed types wherever possible:

```java
@Mock Client mockClient;
@Mock ClientThread mockClientThread;
@Mock GroupFinderConfig mockConfig;
@Mock ChatMessageManager mockChatMessageManager;
@Mock GroupFinderPanelView mockPanel;   // mock the interface, not the Swing class
```

### Making `ScheduledExecutorService.execute()` synchronous

Async lambdas submitted via `executorService.execute(r)` must run inline during tests:

```java
doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
    .when(mockExecutor).execute(any(Runnable.class));
```

Apply this in `@BeforeEach` so all tests share it. Override per-test only when you need
to test the scheduling itself.

### Making `ClientThread.invokeLater()` synchronous

Same pattern — make the runnable execute immediately on the test thread:

```java
private void makeClientThreadSynchronous() {
    doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
        .when(mockClientThread).invokeLater(any(Runnable.class));
}
```

Call this inside any test that exercises a `clientThread.invokeLater(...)` code path.

**Important:** `ClientThread` has two overloads — `invokeLater(Runnable)` and
`invokeLater(BooleanSupplier)`. Always use `any(Runnable.class)`, never bare `any()`,
or Mockito will report an ambiguous method reference:

```java
// Good
verify(mockClientThread, never()).invokeLater(any(Runnable.class));

// Compile error — ambiguous overload
verify(mockClientThread, never()).invokeLater(any());
```

### Testing failure paths in async runnables

When production code submits work via `executorService.execute()` or `clientThread.invokeLater()`,
also test what happens when that runnable throws:

```java
@Test
void refreshListings_whenClientThrows_showsConnectionError() {
    when(mockGroupFinderClient.getGroups(any())).thenThrow(new RuntimeException("timeout"));
    doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
        .when(mockExecutor).execute(any(Runnable.class));

    plugin.refreshListings();

    verify(mockPanel).showError(contains("connection"));
}
```

Failure paths to cover for every async boundary:
- The runnable throws a `RuntimeException` (network error, JSON parse failure)
- The plugin catches the exception and surfaces an error to the user (not silently swallowed)
- The panel/callback itself is null or throws — verify the plugin does not propagate unchecked

### Flushing `SwingUtilities.invokeLater` callbacks

When production code schedules work on the Event Dispatch Thread, flush it
in the test before asserting:

```java
SwingUtilities.invokeAndWait(() -> {});
```

Only do this when the assertion depends on a side effect dispatched via `invokeLater`.

Always let the checked exceptions from `SwingUtilities.invokeAndWait` propagate —
do **not** catch and swallow them. Add `throws Exception` to the test method
signature; JUnit 5 handles it correctly:

```java
// Good — exceptions propagate; test fails if EDT task throws
@Test
void loginScreen_invokesCallback() throws Exception {
    plugin.onGameStateChanged(event);
    SwingUtilities.invokeAndWait(() -> {});
    verify(mockCallback).run();
}

// Bad — swallowing exceptions hides EDT failures; test passes vacuously
@Test
void loginScreen_invokesCallback() {
    plugin.onGameStateChanged(event);
    try { SwingUtilities.invokeAndWait(() -> {}); } catch (Exception ignored) {}
    verify(mockCallback).run();
}
```

### Testing `@Subscribe` event handlers

Call the handler method directly — do not test that RuneLite's event bus wires it up:

```java
// Good
GameStateChanged event = mock(GameStateChanged.class);
when(event.getGameState()).thenReturn(GameState.HOPPING);
plugin.onGameStateChanged(event);
assertFalse(plugin.isInFriendsChat());

// Bad — tests RuneLite infrastructure, not plugin behavior
eventBus.post(new GameStateChanged(...));
```

### Player name NBSP normalisation

RuneLite encodes spaces in player names as `\u00A0` (non-breaking space).
Always test the normalisation boundary: inputs with `\u00A0` must produce outputs
with regular ASCII space `' '`.

```java
when(player.getName()).thenReturn("Bob\u00A0Smith");
// assert the value stored or sent to the API equals "Bob Smith"
```

### Widget IDs in tests

When a test stubs or asserts on `client.getWidget(id, child)`, document the widget
with a comment so the ID is not magic:

```java
// Widget 162,42 = Chatbox.MES_TEXT (the "Enter player name" dialog label)
when(mockClient.getWidget(162, 42)).thenReturn(mockWidget);
```

---

## MockWebServer for HTTP contract tests

Use `MockWebServer` for `GroupFinderClient` — it provides a real TCP server:

```java
private MockWebServer server;
private GroupFinderClient client;

@BeforeEach
void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    GroupFinderConfig config = mock(GroupFinderConfig.class);
    when(config.serverUrl()).thenReturn("http://localhost:" + server.getPort());
    client = new GroupFinderClient(new OkHttpClient(), new Gson(), config);
}

@AfterEach
void tearDown() {
    try { server.shutdown(); } catch (IOException ignored) { }
}
```

Enqueue one response per test. Capture `server.takeRequest()` to assert HTTP verb,
path, query parameters, and request body. Never share server state across tests.

**Network failure tests:** shut the server down inside the test, then make the request.
The `@AfterEach` `try/catch` above handles the harmless double-shutdown:

```java
@Test
void getGroups_onNetworkFailure_returnsEmptyList() throws IOException {
    server.shutdown(); // nothing listening on the port → ConnectException (IOException)

    assertThat(client.getGroups(null)).isEmpty();
}
```

Do not use `SocketPolicy.DISCONNECT_AT_START` — OkHttp may silently retry the request
on the same server, consuming a queued response and making the test non-deterministic.

---

## Mockito settings

Prefer strict stubs — it fails on unused stubs:

```java
@ExtendWith(MockitoExtension.class)
// default is STRICT_STUBS
```

Use `LENIENT` only when a `@BeforeEach` stub is intentionally unused in some tests:

```java
@MockitoSettings(strictness = Strictness.LENIENT)
```

Document why when you use lenient mode.

Scope `@MockitoSettings(strictness = Strictness.LENIENT)` as narrowly as possible.
Applying it at the class level disables unused-stub detection for every mock in every
test — including per-test stubs that should be exercised. If you must use class-level
LENIENT because multiple `@BeforeEach` stubs are intentionally unused in subsets of
tests, document the exact reason in a class-level comment:

```java
// LENIENT: executorService and clientThread stubs in @BeforeEach are not
// exercised in tests that never reach an async code path.
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupFinderPluginBehaviorTest { ... }
```

Never use class-level LENIENT to silence a per-test stub warning. If a stub set
up inside a `@Test` body is unused, either the stub is wrong or the test is
covering the wrong thing — remove it.

### Prefer state assertions over interaction verification

Verify mock interactions only for side effects that are part of the contract
(message sent, panel updated, scheduler invoked). Do not verify every intermediate call.

```java
// Good — verifies an observable outcome
verify(mockPanel).showError("You must be logged in to create a group");

// Bad — verifies internal plumbing
verify(mockGroupFinderClient, times(0)).createGroup(any());
```

### When to use `never()` and `verifyNoInteractions()`

**MUST use `never()`** when a guard or early-return is itself the behavioral contract — i.e., the spec says "this must not happen":

```java
// Guard: not logged in → API must NOT be called
plugin.createGroup(listing);
verify(mockGroupFinderClient, never()).createGroup(any());
```

**MUST use `verifyNoInteractions()`** when the entire mock must stay untouched (proving a branch is fully skipped):

```java
verifyNoInteractions(mockGroupFinderClient);
```

**MUST NOT use `never()` or `verifyNoInteractions()`** when:
- The absence of a call is not the behavioral claim — you already asserted the correct outcome via state or return value
- The mock was simply never stubbed — Mockito strict mode already catches unused stubs
- You are adding it "just to be safe" with no corresponding spec requirement

Default: prefer positive assertions. Reach for `never()` only when the prohibition is the contract.

### `ArgumentCaptor` — when to use it

Use `ArgumentCaptor` when the value passed to a dependency is the behavioral claim:

```java
ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
verify(mockGroupFinderClient).updateGroup(eq("id1"), captor.capture());
assertEquals(5, captor.getValue().get("currentSize"));
```

---

## Forbidden fragility patterns

These are hard bans — do not generate tests that contain them:

| Pattern | Why it is banned |
|---|---|
| `Thread.sleep(N)` | Non-deterministic; use synchronous fakes instead |
| `verify(mock, times(N))` for N > 1 unless exact count is the contract | Brittle to harmless refactors |
| Asserting private field values via reflection | Tests implementation, not behavior |
| Mirror-implementation assertions (`assertEquals(a + b, method(a, b))`) | Re-implements the logic; proves nothing |
| Stubbing methods never called in the test | Dead setup; signals the test is covering the wrong thing |
| `verifyNoInteractions` on mocks unrelated to the scenario | Couples test to irrelevant collaborators |
| `ArgumentCaptor` to assert an exact serialized JSON string | Fragile to field ordering; assert parsed values instead |
| `@BeforeAll` static mutable shared fixtures | Breaks test isolation; use `@BeforeEach` |
| Catching exceptions with `try/catch` to then assert | Use `assertThatThrownBy` instead |
| `assertTrue(result != null)` or `assertFalse(list.isEmpty())` | Use AssertJ: `assertThat(result).isNotNull()`, `isNotEmpty()` |
| `body.contains("FIELD_VALUE")` or any assertion on a raw JSON/text body string | Ties tests to field ordering and serialization details; deserialize and assert on parsed values instead |
| Two test methods that assert the same observable outcome on the same code path | Duplication; delete the weaker one |
| Creating `pom.xml`, `target/`, or Maven artifacts in a Gradle project | This is a Gradle project; never scaffold or invoke Maven tooling |
| `verify(mock).method(contains("partial"))` when the exact error string is a known literal in the production code | Allows misspelled messages to pass; use `isEqualTo("exact message")` for hardcoded literals |

---

## AssertJ over JUnit assertions

Prefer AssertJ's `assertThat(...)` over bare `assertEquals`/`assertTrue`:

```java
// Good
assertThat(result).hasSize(1);
assertThat(result.get(0).getPlayerName()).isEqualTo("Alice");
assertThat(listing.getFriendsChatName()).isEqualTo("FC Owner");

// Acceptable for simple equality
assertEquals("Bob Smith", listing.getPlayerName());
```

AssertJ produces better failure messages and supports fluent chaining.

---

## Parameterized tests

Use `@ParameterizedTest` for rules that vary only by input:

```java
@ParameterizedTest
@ValueSource(strings = { "", " ", "\t" })
void createGroup_blankPlayerName_showsLoginError(String name) { ... }

@ParameterizedTest
@EnumSource(value = GameState.class, names = { "LOGIN_SCREEN", "HOPPING" })
void onGameStateChanged_disconnectingStates_clearInFriendsChat(GameState state) { ... }
```

Use `@CsvSource` for multiple correlated inputs. Use `@MethodSource` for complex objects.

**Do not write a hand-rolled `@Test` that spot-checks specific values already covered by
`@EnumSource`.** If you parameterize over `Activity.class`, every constant is already tested;
adding a separate `@Test` for `CHAMBERS_OF_XERIC` and `OTHER` is pure duplication:

Also forbidden: a `@Test` that asserts a negative proxy condition
(e.g., `assertThat(activity.toString()).doesNotContain("_")`) when that
condition is already fully implied by an existing `@ParameterizedTest`.
Write a new parameterized test only when it exercises a genuinely independent
contract not covered by any existing `@EnumSource` test.

```java
// Bad — redundant with the @ParameterizedTest below
@Test
void toString_returnsDisplayName_forSelectedValues() {
    assertThat(Activity.CHAMBERS_OF_XERIC.toString()).isEqualTo("Chambers of Xeric");
    assertThat(Activity.OTHER.toString()).isEqualTo("Other");
}

// Good — covers every value; the spot-check above adds nothing
@ParameterizedTest
@EnumSource(Activity.class)
void toString_equalsDisplayName(Activity activity) {
    assertThat(activity.toString()).isEqualTo(activity.getDisplayName());
}
```

---

## Arrange-Act-Assert

Every test body follows the AAA pattern, separated by blank lines:

```java
@Test
void createGroup_onApiSuccess_updatesPanel() {
    // Arrange
    when(player.getName()).thenReturn("Bob");
    when(mockClient.getLocalPlayer()).thenReturn(player);
    when(mockClient.getFriendsChatManager()).thenReturn(mockFcm);
    when(mockFcm.getOwner()).thenReturn("OwnerFC");
    when(mockGroupFinderClient.createGroup(any())).thenReturn(new GroupListing());
    when(mockGroupFinderClient.getGroups(any())).thenReturn(List.of(listing));

    // Act
    plugin.createGroup(new GroupListing());

    // Assert
    verify(mockPanel).updateListings(List.of(listing));
}
```

One behavioral claim per test. If a test requires ten verifications it is probably
testing multiple things — split it.

**Two `verify()` calls on different mocks are usually two claims.** Split them unless
the two side-effects are inseparable from the user's perspective (e.g., a delete always
polls and then updates the panel — those two are one indivisible outcome). When you keep
them together, add a comment explaining why they belong in the same test:

```java
// Good — two distinct claims, split into two tests
@Test void whenApiSucceeds_panelIsRefreshed() { ... }
@Test void passesActivityToClient() { ... }

// Acceptable when the two effects are one atomic outcome — document it
@Test void whenApiSucceeds_clientIsCalledAndPanelUpdated() {
    // These are inseparable: the panel update only happens after the client call succeeds.
    verify(mockGroupFinderClient).getGroups(Activity.CHAMBERS_OF_XERIC);
    verify(mockPanel).updateListings(listings);
}
```

**Do not put assertions in the Arrange section.** A sanity check that a precondition
was established is not a test assertion — it obscures the AAA boundary and can make a
test appear to check two transitions at once:

```java
// Bad — assertThat in Arrange muddies the boundary
plugin.onFriendsChatChanged(joinEvent);
assertThat(plugin.isInFriendsChat()).isTrue(); // ← this is not "Assert", it is "Arrange"
plugin.onFriendsChatChanged(leaveEvent);
assertThat(plugin.isInFriendsChat()).isFalse();

// Good — trust the setup; the joined→true case is covered by its own test
plugin.onFriendsChatChanged(joinEvent); // Arrange: get into the right state
plugin.onFriendsChatChanged(leaveEvent); // Act
assertThat(plugin.isInFriendsChat()).isFalse(); // Assert
```

---

## What NOT to test

| Anti-pattern | Why |
|---|---|
| `assertEquals(N, Activity.values().length)` | Breaks on any new enum value |
| Getters/setters on Lombok `@Data` models | No logic to verify |
| That RuneLite's event bus delivers events | Tests the framework, not the plugin |
| Exact call counts when the outcome is what matters | Brittle to refactors |
| Implementation details (private field values via reflection) | Test exposed behaviour instead |
| Static initialisers (loading icons, images) | Skip `startUp()` in plugin tests |

---

## What should be strongly covered

### GroupFinderPlugin
- All validation guards in `createGroup` (null player, null name, no FC)
- NBSP normalisation for player name and FC owner name
- API success path → panel updated
- API failure path → error displayed AND `verify(mockGroupFinderClient, never()).getGroups(any())` — a failed create/delete/update must not silently trigger a panel refresh
- `onFriendsChatChanged` joined/left state transitions + callback invocation
- `onGameStateChanged` LOGIN_SCREEN and HOPPING clear FC state; other states do not
- `joinFriendsChat` null/empty guard, not-logged-in guard, dialog-absent path (guide message), dialog-open path (no guide message)
  Note on the dialog-open path: `client.getCanvas()` returns an AWT `Canvas`.
  Dispatching `KeyEvent` to a null canvas throws `NullPointerException`. To test
  this path, stub `when(mockClient.getCanvas()).thenReturn(new java.awt.Canvas())`
  or extract key-dispatch into a separate injectable collaborator. If neither is
  feasible without broader refactoring, document this gap explicitly in the "report
  gaps" step of the test generation workflow (step 6).
- `refreshListings` non-empty result → `panel.updateListings(list)` called
- `refreshListings` empty-list result → `panel.updateListings(Collections.emptyList())` called, NOT `showError`
- `refreshListings` exception → `panel.showError("Could not connect to server")` (exact string, not `contains(...)`)
- `onFilterChanged` passes the activity through to the client

### GroupFinderClient
- `getGroups(null)` — no query param
- `getGroups(activity)` — correct `?activity=` enum name
- HTTP 2xx response → parsed into list
- HTTP 4xx/5xx → empty list, no exception
- Network failure → empty list, no exception
- Malformed JSON → propagates `RuntimeException` (not silently swallowed)
- `createGroup` — POST to `/api/groups`, body contains fields, returns parsed listing
- `deleteGroup` — DELETE to `/api/groups/{id}`, returns `true`/`false`
- `updateGroup` — PATCH to `/api/groups/{id}`, serialized fields, returns parsed listing

### Activity enum
- `toString()` returns `displayName`, not the enum constant name
- No activity has a null or blank `displayName`

---

## Determinism rules

- **No `Thread.sleep`** — use a synchronous fake executor/scheduler
- **No real network** — use `MockWebServer` for HTTP; mock `GroupFinderClient` for plugin tests
- **No `System.currentTimeMillis()`** in logic under test — inject a `Clock` if you need it
- **No random** — seed or inject if needed
- **No shared mutable state between tests** — `@BeforeEach` resets everything

---

## Test data builders

When arranging a `GroupListing` is repetitive, extract a builder helper in `src/test`:

```java
// src/test/java/com/groupfinder/GroupListingFixture.java
static GroupListing listing() {
    GroupListing g = new GroupListing();
    g.setId("test-id");
    g.setPlayerName("Alice");
    g.setActivity(Activity.CHAMBERS_OF_XERIC);
    g.setCurrentSize(1);
    g.setMaxSize(3);
    return g;
}
```

Helpers live in `src/test` only — never in `src/main`.

When a fixture is defined in `package com.groupfinder` but a test lives in a
sub-package (e.g. `com.groupfinder.client`), do **not** duplicate the fixture as a
private inner class — duplicates drift independently over time. Instead make the
fixture class `public` so it is importable across test packages:

```java
// src/test/java/com/groupfinder/GroupListingFixture.java
public class GroupListingFixture {           // public, not package-private
    public static GroupListing listing() { ... }
}
```

Then `GroupFinderClientTest` imports it directly:
```java
import com.groupfinder.GroupListingFixture;
```

---

## Review checklist before committing a new test

- [ ] Does it fail if the behavior it covers is broken?
- [ ] Does it describe behavior a user or caller cares about?
- [ ] Will it survive harmless refactors (renaming variables, extracting private methods)?
- [ ] Is there a simpler, lower-level place to test this?
- [ ] Is there exactly one reason this test would fail?
- [ ] Are all inputs arranged explicitly (no hidden state from other tests)?

---

## Pre-submission checklist

Before considering the test task complete, verify every item:

- [ ] `./gradlew test` exits 0 — no compile errors, no test failures
- [ ] Every `compileOnly` dependency has been audited; those needed at test
      runtime are in `testRuntimeOnly`
- [ ] No `pom.xml` or `target/` directory exists in the repository root
- [ ] No `@Test` spot-checks duplicate values already covered by `@EnumSource`
- [ ] No raw string body assertions (`body.contains(...)`)
- [ ] All mock field types exactly match the declared field type in the plugin source
- [ ] No test method pair asserts the same observable outcome on the same code path