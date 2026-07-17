# Browser-Service Integration — Remaining Work Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish connecting Look-see consumers to [brandonkindred/browser-service](https://github.com/brandonkindred/browser-service) so production browsing no longer embeds Selenium/Appium in-process.

**Architecture:** LookseeCore already ships a dual-mode shim — `looksee.browsing.mode=local|remote`. Local still uses in-tree `looksee-browser`; remote uses `BrowsingClient` → HTTP `/v1/*` against browser-service, wrapped by `RemoteBrowser` / `RemoteWebElement`. Consumers keep calling `BrowserService`; cutover is a per-consumer env-var flip plus a few remaining remote-unsafe code paths.

**Tech Stack:** Java 17+/Spring Boot, LookseeCore `BrowsingClient` (OpenAPI-generated), browser-service (Spring Boot 3 / Selenium 4 / Appium 8), Terraform Cloud Run (`LookseeIaC`), Micrometer `browser_service_calls`.

## Global Constraints

- Default `looksee.browsing.mode` stays `local` until a consumer is explicitly flipped (rollback = set mode back to `local` + redeploy).
- Consumers must never call `browser.getDriver()` or raw Selenium `WebDriver` APIs on remote paths.
- OpenAPI contract source of truth lives in browser-service (`openapi/generated.yaml`); Look-see copies it into `LookseeCore/looksee-browsing-client/src/main/resources/openapi.yaml`.
- Per-consumer mode pins override any shared `looksee_browsing_mode` tfvar (see phase-4b/4c design).
- Metric contract: Micrometer Timer `browser_service_calls` with tags `operation`, `outcome`, and consumer common-tag `consumer`.
- Do not delete `looksee-browser` until Phase 5 (all consumers remote + calm window).

---

## Part A — Impact Inventory (current state)

### Already connected (shim complete)

| Layer | Path | Role |
|---|---|---|
| Local engine | `LookseeCore/looksee-browser/` | In-process Selenium 3 / Appium 7 (`Browser`, `MobileDevice`, factories) |
| HTTP client | `LookseeCore/looksee-browsing-client/` | `BrowsingClient` facade + OpenAPI-generated client |
| Mode fork | `LookseeCore/looksee-core/.../BrowserService.java`, `TestService.java` | `getConnection` / capture paths fork on `LookseeBrowsingProperties.mode` |
| Remote adapters | `.../services/browser/RemoteBrowser.java`, `RemoteWebElement.java`, `RemoteAlert.java`, `PageStateAdapter.java` | Map Browser/WebElement APIs → BrowsingClient |
| Smoke check | `.../services/health/CapturePageSmokeCheck.java` | Opt-in remote watchdog |
| Config | `LookseeBrowsingProperties`, `BrowsingClientConfiguration` | Beans only when `mode=remote` |

Phases **3 → 3f** and LookseeCore prep for **4a.1 / 4a.3 / 4a.4** are done (see `LookseeCore/CHANGELOG.md` through 0.8.2). LookseeCore is now **1.1.0**.

### Bucket A — browser session consumers (must cut over)

| Consumer | Opens session? | `looksee.browsing` yaml | IaC env wiring | Remote-safe call path? | Remaining |
|---|---|---|---|---|---|
| **PageBuilder** | Yes (`getConnection` → `buildPageState` → `getDomElementStates`) | Wired | Wired (`page_builder_cloud_run`) | Yes | Staging/prod **ops flip** (4a.5 / 4a.6) |
| **journeyExecutor** | Yes (`getConnection` → `StepExecutor` → `buildPage`) | Wired | Wired (`journey_executor_browsing_mode`) | Yes | Staging/prod **ops flip** (4c) |
| **element-enrichment** | Yes (`getConnection` → `navigateTo` → `removeDriftChat` → `enrichElementStates`) | **Missing** | **Missing** (no Cloud Run module) | Code yes | yaml + metrics + IaC + flip (4b) |

### Bucket B — impacted but no live session (low priority)

| Module | Touch | Action |
|---|---|---|
| `journeyExpander` | `BrowserType`, static `BrowserService` helpers | None for cutover |
| `informationArchitectureAudit` | Static xpath/CSS helpers | None |
| `visualDesignAudit` | `HtmlUtils` only | None |
| `contentAudit` | Selenium exception types in resilience4j only | Optional: also retry `BrowsingClientException` later |
| `CrawlerAPI` | `@Autowired Crawler` (raw WebDriver helpers) + Selenium dep | Stay local; migrate later or leave until Phase 5 |
| `AuditManager`, `audit-service`, `journeyErrors`, `journey-map-cleanup`, `look-see-front-end-broadcaster` | A11yCore / transitive only | None |
| `qa-testbed` | Static HTML fixtures | Unrelated |

### Remaining remote-unsafe code (LookseeCore)

| Site | Problem | Blocks |
|---|---|---|
| `RemoteWebElement.findElement(By)` / `findElements(By)` | Still throw `UnsupportedOperationException` | Nested finds; `Table`; form field walks |
| `BrowserService.extractAllForms` (~3336–3446) | Uses `browser.getDriver()` | Any remote caller of form extraction (`@Deprecated`, currently low traffic) |
| `com.looksee.browsing.table.Table` | Calls `WebElement.findElements(By.xpath(...))` | Table parsing in remote mode |
| `Crawler` / `ElementUtils` taking raw `WebDriver` | Not remote-safe by design | CrawlerAPI only |

### External gate

browser-service must be deployed (staging + prod URLs, `/healthz`/`/readyz`, auth/OIDC or network policy, capacity alerts) before any consumer flips to `remote`. Documented in `browser-service/phase-4-consumer-cutover.md` Prerequisites.

### Existing phase docs (do not reinvent)

| Doc | Covers |
|---|---|
| `browser-service/phase-4-consumer-cutover.md` | Umbrella cutover |
| `browser-service/phase-4a5-*.md` / `phase-4a6-*.md` | PageBuilder staging/prod |
| `browser-service/phase-4b-element-enrichment-cutover.md` | element-enrichment |
| `browser-service/phase-4c-journey-executor-cutover.md` | journeyExecutor |

This plan focuses on **gaps still open in Look-see code** plus a sequenced execution order that stitches those docs together.

---

## File map (remaining Look-see changes)

| File | Responsibility |
|---|---|
| `element-enrichment/src/main/resources/application.yml` | Bind `LOOKSEE_BROWSING_*` |
| `element-enrichment/.../config/BrowsingClientMetricsConfig.java` | `consumer=element-enrichment` common tag |
| `LookseeIaC/GCP/modules.tf` + `variables.tf` | element-enrichment Cloud Run + browsing env (if prodized) |
| `LookseeCore/.../RemoteWebElement.java` | Nested find via xpath compose or new API |
| `LookseeCore/.../BrowsingClient.java` (+ openapi if new endpoint) | Optional `findChildren` helper |
| `LookseeCore/.../BrowserService.java` | Migrate `extractAllForms` / `buildFormFields` off `getDriver()` |
| `LookseeCore/.../browsing/table/Table.java` | Use remote-safe nested finds |
| Consumer tfvars | Flip `*_browsing_mode` local → remote per env |

---

### Task 1: Confirm browser-service readiness (external gate)

**Files:** none in Look-see (ops checklist)

**Interfaces:**
- Consumes: deployed browser-service `/healthz`, `/readyz`, `/v1/sessions`
- Produces: staging + prod base URLs for `LOOKSEE_BROWSING_SERVICE_URL`

- [ ] **Step 1: Verify health endpoints**

```bash
curl -fsS "$BROWSER_SERVICE_STAGING_URL/healthz"
curl -fsS "$BROWSER_SERVICE_STAGING_URL/readyz"
# Expected: HTTP 200
```

- [ ] **Step 2: Smoke a session with a valid OIDC token (or API key per service docs)**

```bash
curl -fsS -X POST "$BROWSER_SERVICE_STAGING_URL/v1/sessions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"browser_type":"chrome"}'
# Expected: JSON with session_id
```

- [ ] **Step 3: Record URLs in ops notes / tfvars secrets** — do not commit secrets.

- [ ] **Step 4: Commit** — N/A (ops only). Block Tasks 5–7 until this is green.

---

### Task 2: Wire element-enrichment browsing config + metrics

**Files:**
- Modify: `element-enrichment/src/main/resources/application.yml`
- Create: `element-enrichment/src/main/java/com/looksee/pageBuilder/config/BrowsingClientMetricsConfig.java`
- Create: `element-enrichment/src/test/java/com/looksee/pageBuilder/config/BrowsingClientMetricsConfigTest.java`
- Reference: `PageBuilder/src/main/java/com/looksee/pageBuilder/config/BrowsingClientMetricsConfig.java`

**Interfaces:**
- Consumes: `LookseeBrowsingProperties` binding from LookseeCore
- Produces: env-driven remote mode + Micrometer common tag `consumer=element-enrichment`

- [ ] **Step 1: Write the failing metrics-config test**

```java
package com.looksee.pageBuilder.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BrowsingClientMetricsConfigTest {
    @Test
    void addsConsumerCommonTag() {
        MeterRegistry registry = new SimpleMeterRegistry();
        new BrowsingClientMetricsConfig().commonTags().apply(registry);
        registry.counter("probe").increment();
        assertEquals(
            "element-enrichment",
            registry.find("probe").counter().getId().getTag("consumer"));
        assertNotNull(registry.find("probe").counter());
    }
}
```

(Adapt to match PageBuilder's exact `MeterFilter` pattern if the helper method name differs.)

- [ ] **Step 2: Run test to verify it fails**

```bash
cd element-enrichment && mvn -q test -Dtest=BrowsingClientMetricsConfigTest
```

Expected: FAIL — class/config missing.

- [ ] **Step 3: Append browsing block to `application.yml`**

```yaml
# LookseeCore browsing configuration. Default mode is `local`.
# Flip LOOKSEE_BROWSING_MODE=remote + set LOOKSEE_BROWSING_SERVICE_URL
# to route through brandonkindred/browser-service.
looksee:
    browsing:
        mode: ${LOOKSEE_BROWSING_MODE:local}
        service-url: ${LOOKSEE_BROWSING_SERVICE_URL:}
        connect-timeout: ${LOOKSEE_BROWSING_CONNECT_TIMEOUT:5s}
        read-timeout: ${LOOKSEE_BROWSING_READ_TIMEOUT:120s}
        smoke-check:
            enabled: ${LOOKSEE_BROWSING_SMOKE_CHECK_ENABLED:false}
            interval: ${LOOKSEE_BROWSING_SMOKE_CHECK_INTERVAL:60s}
            target-url: ${LOOKSEE_BROWSING_SMOKE_CHECK_TARGET_URL:https://example.com}
            browser: ${LOOKSEE_BROWSING_SMOKE_CHECK_BROWSER:CHROME}
```

- [ ] **Step 4: Add `BrowsingClientMetricsConfig` mirroring PageBuilder**

Copy `PageBuilder/.../BrowsingClientMetricsConfig.java` into element-enrichment and set the common tag to `"element-enrichment"`.

- [ ] **Step 5: Run tests**

```bash
cd element-enrichment && mvn -q test -Dtest=BrowsingClientMetricsConfigTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add element-enrichment/src/main/resources/application.yml \
  element-enrichment/src/main/java/com/looksee/pageBuilder/config/BrowsingClientMetricsConfig.java \
  element-enrichment/src/test/java/com/looksee/pageBuilder/config/BrowsingClientMetricsConfigTest.java
git commit -m "$(cat <<'EOF'
feat(element-enrichment): wire looksee browsing env vars and metrics

EOF
)"
```

---

### Task 3: Implement nested `RemoteWebElement.findElement(s)` (phase 3g)

**Files:**
- Modify: `LookseeCore/looksee-core/src/main/java/com/looksee/services/browser/RemoteWebElement.java`
- Modify: `LookseeCore/looksee-core/src/test/java/com/looksee/services/browser/RemoteWebElementTest.java`
- Modify: `LookseeCore/looksee-core/src/test/java/com/looksee/services/browser/RemoteWebElementWiredMethodsTest.java`
- Optionally modify: `LookseeCore/looksee-browsing-client/.../BrowsingClient.java` if a dedicated children endpoint is preferred

**Interfaces:**
- Consumes: `BrowsingClient.findElement(sessionId, xpath)` (existing) **or** new `/v1/sessions/{id}/element/{handle}/find-children`
- Produces: working nested finds so `Table` and form walks can proceed

**Preferred approach (no server change):** client-side xpath composition for relative `By.xpath("./...")` / `By.tagName(...)` only; throw clear errors for unsupported `By` types.

- [ ] **Step 1: Replace the “still throws” assertions with expected-success tests**

In `RemoteWebElementTest`, change the nested-find cases to:

```java
@Test
void findElementsByRelativeXpathDelegatesToClient() {
    BrowsingClient client = mock(BrowsingClient.class);
    ElementState child = new ElementState(); // populate handle + attrs as existing tests do
    when(client.findElement(eq("sess"), eq("//form[1]/tr"))).thenReturn(/* found state */);

    RemoteWebElement parent = new RemoteWebElement("sess", "el_form", Map.of(), client);
    // assume parent cached xpath "//form[1]" or compose via executeScript getXPath —
    // implement composeRelativeXpath(parentXpath, By) in RemoteWebElement

    List<WebElement> kids = parent.findElements(By.xpath("./tr"));
    assertEquals(1, kids.size());
}
```

(Use the same mocking style as `RemoteWebElementWiredMethodsTest`.)

- [ ] **Step 2: Run tests — expect FAIL**

```bash
cd LookseeCore && ./mvnw -pl looksee-core -am test -Dtest=RemoteWebElementTest,RemoteWebElementWiredMethodsTest
```

- [ ] **Step 3: Implement composition helper on `RemoteWebElement`**

```java
static String composeRelativeXpath(String parentXpath, By by) {
    String byStr = by.toString(); // "By.xpath: ./tr" or "By.tagName: input"
    if (byStr.startsWith("By.xpath: ")) {
        String rel = byStr.substring("By.xpath: ".length()).trim();
        if (rel.startsWith("./")) {
            return parentXpath + rel.substring(1); // "//form[1]" + "/tr"
        }
        if (rel.startsWith(".//")) {
            return parentXpath + rel.substring(1);
        }
        throw new UnsupportedOperationException(
            "RemoteWebElement: only relative xpath (./ or .//) supported, got " + rel);
    }
    if (byStr.startsWith("By.tagName: ")) {
        String tag = byStr.substring("By.tagName: ".length()).trim();
        return parentXpath + "//" + tag;
    }
    throw new UnsupportedOperationException(
        "RemoteWebElement.findElements: unsupported By type: " + by);
}
```

Store `parentXpath` on construction (from `RemoteBrowser.findElement` response / attribute map). For each match from a multi-find, prefer adding a server `find-children` endpoint if composition cannot return multiple nodes — if `BrowsingClient` only has singular find, implement multi-find via `executeScript` that returns handles, **or** extend OpenAPI with `findElements` and regenerate the client.

**Decision gate:** If singular find-only composition is insufficient for `Table` (needs multiple `./tr`), implement OpenAPI:

```yaml
# POST /v1/sessions/{session_id}/element/find-children
# body: { element_handle, xpath }
# response: { elements: [ ElementState, ... ] }
```

Then regenerate `looksee-browsing-client` and add `BrowsingClient.findChildElements(sessionId, handle, xpath)`.

- [ ] **Step 4: Wire `findElement` / `findElements` to stop throwing**

```java
@Override
public List<WebElement> findElements(By by) {
    String xpath = composeRelativeXpath(requireParentXpath(), by);
    List<ElementState> states = requireClient("findElements")
        .findChildElements(sessionId, elementHandle, xpath);
    return states.stream()
        .map(s -> new RemoteWebElement(sessionId, s.getElementHandle(), s.getAttributes(), client))
        .collect(Collectors.toList());
}
```

- [ ] **Step 5: Re-run tests — expect PASS**

```bash
cd LookseeCore && ./mvnw -pl looksee-core -am test -Dtest=RemoteWebElementTest,RemoteWebElementWiredMethodsTest
```

- [ ] **Step 6: Commit**

```bash
git add LookseeCore/looksee-core LookseeCore/looksee-browsing-client
git commit -m "$(cat <<'EOF'
feat(core): support nested RemoteWebElement finds for remote mode

EOF
)"
```

---

### Task 4: Make `Table` remote-safe

**Files:**
- Modify: `LookseeCore/looksee-core/src/main/java/com/looksee/browsing/table/Table.java`
- Modify/Create: `LookseeCore/looksee-core/src/test/java/com/looksee/browsing/table/TableRemoteModeTest.java`

**Interfaces:**
- Consumes: Task 3 nested finds
- Produces: `Table` works with `RemoteWebElement` roots

- [ ] **Step 1: Write failing test with mocked `RemoteWebElement` children**

```java
@Test
void parsesHeaderRowsViaFindElements() {
    WebElement tableRoot = mock(RemoteWebElement.class); // or real RemoteWebElement + mock client
    WebElement thead = mock(WebElement.class);
    when(tableRoot.findElements(By.xpath("./thead"))).thenReturn(List.of(thead));
    // ... stub ./tr and ./th as Table.java expects
    Table table = new Table(tableRoot);
    assertFalse(table.getHeaders().isEmpty());
}
```

(Match the actual `Table` constructor / API in the file.)

- [ ] **Step 2: Run — expect FAIL until Task 3 lands**

```bash
cd LookseeCore && ./mvnw -pl looksee-core test -Dtest=TableRemoteModeTest
```

- [ ] **Step 3: If `Table` only needs standard `findElements`, no code change beyond Task 3** — delete any local-only assumptions. If it casts to concrete Selenium types, replace with `WebElement` interface usage only.

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add LookseeCore/looksee-core/src/main/java/com/looksee/browsing/table \
  LookseeCore/looksee-core/src/test/java/com/looksee/browsing/table
git commit -m "$(cat <<'EOF'
fix(core): make browsing Table helper work with RemoteWebElement

EOF
)"
```

---

### Task 5: Migrate deprecated `extractAllForms` off `getDriver()`

**Files:**
- Modify: `LookseeCore/looksee-core/src/main/java/com/looksee/services/BrowserService.java` (approx. 3329–3458)
- Create: `LookseeCore/looksee-core/src/test/java/com/looksee/services/BrowserServiceExtractAllFormsRemoteTest.java`

**Interfaces:**
- Consumes: `browser.getCurrentUrl()`, `browser.findElement` / multi-find via xpath `//form`, `browser.extractAttributes`, Task 3 nested finds for `form_elem.findElements(By.tagName("input"))`
- Produces: form extraction that works on `RemoteBrowser`

- [ ] **Step 1: Write failing remote-mode test**

```java
@Test
void extractAllFormsDoesNotCallGetDriver() throws Exception {
    Browser browser = mock(RemoteBrowser.class);
    when(browser.getCurrentUrl()).thenReturn("https://example.com/form");
    // stub find-all-forms via Browser API you introduce, e.g. browser.findElements("//form")
    Set<Form> forms = browserService.extractAllForms(1L, domain, browser);
    verify(browser, never()).getDriver();
    assertNotNull(forms);
}
```

- [ ] **Step 2: Run — FAIL** (still hits `getDriver()`)

```bash
cd LookseeCore && ./mvnw -pl looksee-core test -Dtest=BrowserServiceExtractAllFormsRemoteTest
```

- [ ] **Step 3: Add mode-agnostic multi-find on `Browser` if missing**

```java
// Browser.java — local default
public List<WebElement> findElements(String xpath) {
    return getDriver().findElements(By.xpath(xpath));
}
// RemoteBrowser override → BrowsingClient find-children from document root
```

Replace in `extractAllForms`:

```java
log.info("extracting forms from page with url :: {}", browser.getCurrentUrl());
List<WebElement> form_elements = browser.findElements("//form");
```

Replace `uniqifyXpath(..., browser.getDriver())` / `generateXpath(..., browser.getDriver())` with overloads that take `Browser` (or xpath-only helpers already used elsewhere in `BrowserService`). Prefer existing `BrowserService` xpath helpers that do not need a live driver when the element is already a `RemoteWebElement` with a known xpath attribute.

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add LookseeCore/looksee-core/src/main/java/com/looksee/services/BrowserService.java \
  LookseeCore/looksee-browser/src/main/java/com/looksee/browser/Browser.java \
  LookseeCore/looksee-core/src/main/java/com/looksee/services/browser/RemoteBrowser.java \
  LookseeCore/looksee-core/src/test/java/com/looksee/services/BrowserServiceExtractAllFormsRemoteTest.java
git commit -m "$(cat <<'EOF'
refactor(core): remove getDriver from extractAllForms for remote mode

EOF
)"
```

---

### Task 6: PageBuilder remote cutover (ops — follow existing 4a.5/4a.6)

**Files:**
- Modify (tfvars only): LookseeIaC staging/prod — `looksee_browsing_mode` / page-builder smoke-check flags
- Reference: `browser-service/phase-4a5-pagebuilder-staging-cutover.md`, `phase-4a6-pagebuilder-prod-cutover.md`

**Interfaces:**
- Consumes: Task 1 URLs; PageBuilder already has yaml + metrics
- Produces: PageBuilder prod on `mode=remote`

- [ ] **Step 1: Staging flip** — set `LOOKSEE_BROWSING_MODE=remote`, service URL, `page_builder_smoke_check_enabled=true`.
- [ ] **Step 2: 48h burn-in** — watch `browser_service_calls_seconds_count{consumer="page-builder"}` and `browser_service_smoke_checks`.
- [ ] **Step 3: Prod flip** — same env; 1h observation + 7-day calm before Task 7/8.
- [ ] **Step 4: Rollback drill documented** — unset mode / set `local` + redeploy within 1h SLA.

---

### Task 7: element-enrichment IaC + remote cutover (4b)

**Files:**
- Modify: `LookseeIaC/GCP/modules.tf`, `variables.tf`, env tfvars
- Prerequisite: Task 2 merged; resolve open question from `phase-4b-element-enrichment-cutover.md` (Cloud Run module missing today)

**Interfaces:**
- Consumes: Task 2 yaml; Task 6 calm window preferred
- Produces: element-enrichment on remote

- [ ] **Step 1: Resolve deployment home** — either add `element_enrichment_cloud_run` module (mirror page-builder) **or** wire env vars wherever it actually deploys.
- [ ] **Step 2: Add tfvars** `element_enrichment_browsing_mode` (default `"local"`), `element_enrichment_smoke_check_enabled` (default `false`).
- [ ] **Step 3: Staging flip → 48h burn-in → prod flip** per `phase-4b-element-enrichment-cutover.md`.
- [ ] **Step 4: Commit IaC** with messages matching that doc’s Commit 2 / 3a / 3b titles.

---

### Task 8: journeyExecutor remote cutover (4c)

**Files:**
- Modify: LookseeIaC tfvars `journey_executor_browsing_mode`
- Reference: `browser-service/phase-4c-journey-executor-cutover.md`

**Interfaces:**
- Consumes: yaml already wired; Task 6/7 stability
- Produces: journeyExecutor on remote

- [ ] **Step 1: Confirm `AuditController` still uses `browser.getCurrentUrl()` / `StepExecutor` (no `getDriver()`)** — re-grep:

```bash
rg "getDriver\\(" journeyExecutor --glob '*.java'
# Expected: no hits outside tests
```

- [ ] **Step 2: Staging flip with smoke-check → 48h → prod flip.**
- [ ] **Step 3: Commit tfvars only.**

---

### Task 9: Phase 5 cleanup (after all Bucket A remote + calm)

**Files:**
- Delete / stop publishing: `LookseeCore/looksee-browser` as a runtime dependency of consumers
- Modify: `LookseeBrowsingProperties` — remove `LOCAL` or hard-default `REMOTE`
- Modify: `looksee-models` — keep enums without Selenium (extract thin `looksee-browser-enums` jar if needed)
- Modify: remove duplicate direct Selenium deps from `element-enrichment`, `CrawlerAPI`, `journeyErrors`, `journey-map-cleanup`, `look-see-front-end-broadcaster` poms where unused
- Decide: migrate or retire `Crawler` bean used by CrawlerAPI

**Interfaces:**
- Consumes: all Bucket A on remote for ≥7 days each
- Produces: LookseeCore major bump; browser-service is sole engine host

- [ ] **Step 1: Grep for residual local-only APIs**

```bash
rg "BrowserConnectionHelper|selenium\\.urls|getDriver\\(" --glob '*.java' \
  | rg -v 'looksee-browser|test/'
```

- [ ] **Step 2: Extract enums to a Selenium-free module if models still depend on `A11yBrowser`.**
- [ ] **Step 3: Remove `mode=local` path and `looksee-browser` module; major version bump; update `LOOKSEE_CORE_VERSION`.**
- [ ] **Step 4: Commit as a dedicated breaking-change PR.**

---

## Execution order (summary)

```
Task 1  browser-service deployed          (external)
Task 2  element-enrichment yaml+metrics   (code, unblocked now)
Task 3  nested RemoteWebElement finds     (code, unblocked now)
Task 4  Table remote-safe                 (after 3)
Task 5  extractAllForms off getDriver     (after 3)
Task 6  PageBuilder flip                  (after 1)
Task 7  element-enrichment flip           (after 1, 2, prefer after 6)
Task 8  journeyExecutor flip              (after 6/7 calm)
Task 9  delete local engine               (after all remote)
```

Tasks 2–5 can proceed in Look-see **before** production flips. Tasks 6–8 are config/ops. Task 9 is the final extraction.

---

## Self-review

1. **Spec coverage:** Inventory covers all monorepo modules; remaining remote blockers (findElements, forms, Table, element-enrichment config, three cutovers, phase 5) each have a task. CrawlerAPI deferred explicitly.
2. **Placeholders:** No TBD steps; open question for element-enrichment IaC is called out as a decision gate inside Task 7 Step 1.
3. **Type consistency:** Nested finds go through `BrowsingClient` + `RemoteWebElement`; form extraction uses `Browser.findElements` / `getCurrentUrl` — same names referenced across Tasks 3–5.

---

## Related prior art in-repo

Detailed ops playbooks already live under `browser-service/phase-4*.md`. Prefer those for burn-in PromQL, rollback SLA, and exact tfvar names when executing Tasks 6–8.
