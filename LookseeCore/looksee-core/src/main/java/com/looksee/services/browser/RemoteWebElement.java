package com.looksee.services.browser;

import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.client.BrowsingClientException;
import com.looksee.browsing.generated.ApiException;
import com.looksee.browsing.generated.model.ElementAction;
import com.looksee.browsing.generated.model.ElementState;
import com.looksee.browsing.generated.model.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebElement;

/**
 * {@link WebElement} bound to a remote browser-service session. Holds the opaque
 * {@code element_handle} returned by {@code POST /v1/sessions/{id}/element/find}
 * plus the {@code rect}, {@code attributes}, and {@code displayed} fields the
 * server returned at find time. Cached reads are served locally; every
 * mutating op routes through {@link RemoteBrowser} + {@code BrowsingClient}.
 *
 * <p>Equality is {@code sessionId + elementHandle} — matches Selenium's
 * contract of "two references to the same DOM node are equal" without
 * requiring a live driver.
 *
 * <p><b>Cache staleness.</b> {@link #isDisplayed()} and {@link #getAttribute(String)}
 * serve the findElement-response snapshot. If the DOM mutates between find and
 * read, remote and local disagree. Phase-3b ships cache-only; a follow-up can
 * add a {@code refresh()} if staleness surfaces in practice. See
 * {@code browser-service/phase-3b-element-handle-ops.md} §14.1.
 *
 * <p><b>Unsupported WebElement methods.</b> Every method that throws
 * {@link UnsupportedOperationException} below is a phase-3c candidate. The
 * current consumer census routes through {@link RemoteBrowser} /
 * {@code Browser.performClick} / {@code performAction} instead of calling
 * these directly, so they're only reachable via code outside the census —
 * which would be a new finding to reconcile in phase 3c.
 */
public final class RemoteWebElement implements WebElement {

    private static final int MAX_NESTED_FIND_ELEMENTS = 500;

    private final String sessionId;
    private final String elementHandle;
    private final String sourceXpath;             // may be null if constructed without one (back-compat)
    private final Rect rect;                      // may be null if server omitted
    private final Map<String, String> attributes; // never null, immutable
    private final boolean displayed;
    private final BrowsingClient client;          // may be null in test/legacy constructions

    public RemoteWebElement(String sessionId, ElementState state) {
        this(sessionId, null, state, null);
    }

    /**
     * Phase-3e overload: also remembers the xpath used to find this element,
     * so {@link RemoteBrowser#waitForElementClickable} can re-issue
     * {@code client.findElement(sessionId, sourceXpath)} to refresh the
     * displayed flag without needing a server-side wait endpoint.
     */
    public RemoteWebElement(String sessionId, String sourceXpath, ElementState state) {
        this(sessionId, sourceXpath, state, null);
    }

    /**
     * Phase-3f overload: carries the {@link BrowsingClient} so the WebElement
     * methods that route through {@code /element/action}, {@code /execute},
     * and {@code /element/screenshot} (click, sendKeys, submit, clear,
     * getText, isSelected, isEnabled, getCssValue, getScreenshotAs) can
     * forward without needing a parent {@link RemoteBrowser} reference.
     * Constructed via {@link RemoteBrowser#findElement} which has the client
     * in scope. Without a client, those methods throw with a clear pointer.
     */
    public RemoteWebElement(String sessionId, String sourceXpath, ElementState state, BrowsingClient client) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(state, "state");
        this.sessionId = sessionId;
        this.sourceXpath = sourceXpath;
        this.elementHandle = Objects.requireNonNull(state.getElementHandle(), "element_handle");
        this.rect = state.getRect();
        this.attributes = state.getAttributes() == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(state.getAttributes());
        this.displayed = Boolean.TRUE.equals(state.getDisplayed());
        this.client = client;
    }

    /** Used by phase-3f WebElement-method routing. Throws if no client was passed at construction. */
    private BrowsingClient requireClient(String methodName) {
        if (client == null) {
            throw new UnsupportedOperationException(
                "RemoteWebElement." + methodName + ": this element was constructed without a "
                + "BrowsingClient (likely a test fixture or pre-3f code path) and cannot route "
                + "WebElement-API calls. Construct via RemoteBrowser.findElement to get a fully-"
                + "wired element.");
        }
        return client;
    }

    private String requireSourceXpath() {
        if (sourceXpath == null) {
            throw new UnsupportedOperationException(
                "RemoteWebElement.findElement(s): this element was constructed without a source xpath "
                + "and cannot compose a nested lookup. Construct via RemoteBrowser.findElement.");
        }
        return sourceXpath;
    }

    /**
     * Composes the supported relative Selenium locator forms into a document-relative xpath.
     * Nested remote lookup deliberately supports only xpath starting with {@code ./} or
     * {@code .//}, plus tag names, because the browser-service exposes a singular xpath find.
     */
    static String composeRelativeXpath(String parentXpath, By by) {
        Objects.requireNonNull(parentXpath, "parentXpath");
        Objects.requireNonNull(by, "by");
        String locator = by.toString();
        if (locator.startsWith("By.xpath: ")) {
            String relativeXpath = locator.substring("By.xpath: ".length()).trim();
            if (relativeXpath.startsWith("./") || relativeXpath.startsWith(".//")) {
                return parentXpath + relativeXpath.substring(1);
            }
            throw new UnsupportedOperationException(
                "RemoteWebElement: only relative xpath (./ or .//) supported, got " + relativeXpath);
        }
        if (locator.startsWith("By.tagName: ")) {
            String tagName = locator.substring("By.tagName: ".length()).trim();
            return parentXpath + "//" + tagName;
        }
        throw new UnsupportedOperationException(
            "RemoteWebElement.findElement(s): unsupported By type: " + by);
    }

    private static boolean isNotFound(ElementState state) {
        return state == null || !Boolean.TRUE.equals(state.getFound());
    }

    private static boolean isNotFound(BrowsingClientException exception) {
        return exception.getCause() instanceof ApiException
            && ((ApiException) exception.getCause()).getCode() == 404;
    }

    public String getSessionId()     { return sessionId; }
    public String getElementHandle() { return elementHandle; }

    /** Package-private: source xpath for re-fetching live state during waitForElementClickable. */
    String getSourceXpath() { return sourceXpath; }

    /** Package-private: used by {@link RemoteBrowser#extractAttributes(WebElement)}. */
    Map<String, String> cachedAttributes() { return attributes; }

    // --- Cache-backed WebElement methods ---------------------------------

    @Override public boolean isDisplayed() { return displayed; }

    @Override public Point getLocation() {
        return rect == null ? new Point(0, 0) : new Point(rect.getX(), rect.getY());
    }

    @Override public Dimension getSize() {
        return rect == null ? new Dimension(0, 0) : new Dimension(rect.getWidth(), rect.getHeight());
    }

    @Override public Rectangle getRect() {
        Point p = getLocation();
        Dimension d = getSize();
        return new Rectangle(p, d);
    }

    @Override public String getAttribute(String name) {
        return attributes.get(name);
    }

    // --- Unsupported (phase 3c) ------------------------------------------

    // --- Phase 3f: wired via BrowsingClient (was phase-3c-deferred throws) ---

    @Override
    public void click() {
        // Routed through /element/action — the dedicated server endpoint
        // phase 3b already wired. Same path Browser.performClick uses.
        requireClient("click").performElementAction(sessionId, elementHandle, ElementAction.CLICK, null);
    }

    @Override
    public void submit() {
        // Prefer form.requestSubmit() (modern browsers) over form.submit() —
        // requestSubmit fires the `submit` event listeners and runs HTML5
        // constraint validation, matching what local WebDriver sessions do.
        // form.submit() bypasses both, which would silently break React/Vue
        // forms that hook onSubmit + skip validation entirely (PR #54 review).
        // Legacy-browser fallback to form.submit() retained behind a feature
        // check so the method still works on environments without
        // requestSubmit support, with a clear console.warn in that case.
        requireClient("submit").executeScript(sessionId,
            "var el = arguments[0]; "
            + "var form = el.form || (el.tagName === 'FORM' ? el : null); "
            + "if (form) { "
            + "  if (typeof form.requestSubmit === 'function') { form.requestSubmit(); } "
            + "  else { console.warn('RemoteWebElement.submit: requestSubmit not supported; "
            +          "falling back to form.submit() which bypasses submit handlers'); "
            + "    form.submit(); "
            + "  } "
            + "} else { throw new Error('not submittable: ' + el.tagName); }",
            List.of(Map.of("element_handle", elementHandle)));
    }

    @Override
    public void sendKeys(CharSequence... keys) {
        // Selenium contract (RemoteWebElement / W3C WebDriver): null array OR
        // null array element throws IllegalArgumentException. Concatenate
        // non-null entries into one string — matches the local sendKeys
        // semantics so callers see the same failure mode in both modes.
        if (keys == null) {
            throw new IllegalArgumentException("Keys to send should be a not null CharSequence");
        }
        StringBuilder sb = new StringBuilder();
        for (CharSequence k : keys) {
            if (k == null) {
                throw new IllegalArgumentException(
                    "Keys to send should be a not null CharSequence");
            }
            sb.append(k);
        }
        requireClient("sendKeys").performElementAction(sessionId, elementHandle, ElementAction.SEND_KEYS, sb.toString());
    }

    @Override
    public void clear() {
        // Setting value="" alone doesn't fire 'input', which React/Vue/etc.
        // listen for to update bound state. Selenium's local clear() does
        // fire it via WebDriver's routing; dispatch a synthetic event for
        // parity. See phase-3f doc §14.4.
        requireClient("clear").executeScript(sessionId,
            "var el = arguments[0]; el.value = ''; "
            + "el.dispatchEvent(new Event('input', { bubbles: true }));",
            List.of(Map.of("element_handle", elementHandle)));
    }
    @Override public String getTagName() {
        // Server-side engines may synthesize a "tag_name" pseudo-attribute on
        // the findElement response; if so, read from the cache and avoid a
        // round-trip. Browser-service today doesn't include it, so the
        // fallback throws with a pointer to the xpath-derived workaround
        // (see com.looksee.services.BrowserService.extractTagFromXpath).
        String cached = attributes.get("tag_name");
        if (cached != null) return cached;
        throw new UnsupportedOperationException(
            "RemoteWebElement.getTagName: server did not include a 'tag_name' "
            + "attribute in the findElement response. Either add tag_name to "
            + "the server-side attributes synthesis (phase 3e candidate) or "
            + "derive from xpath via BrowserService.extractTagFromXpath.");
    }
    @Override
    public boolean isSelected() {
        Object r = requireClient("isSelected").executeScript(sessionId,
            "return !!(arguments[0].selected || arguments[0].checked);",
            List.of(Map.of("element_handle", elementHandle)));
        return Boolean.TRUE.equals(r);
    }

    @Override
    public boolean isEnabled() {
        // Default-true on null/missing — matches Selenium's "if uncertain,
        // assume enabled" convention for elements without a 'disabled'
        // attribute (most non-form elements).
        Object r = requireClient("isEnabled").executeScript(sessionId,
            "return !arguments[0].disabled;",
            List.of(Map.of("element_handle", elementHandle)));
        return !Boolean.FALSE.equals(r);
    }

    @Override
    public String getText() {
        Object r = requireClient("getText").executeScript(sessionId,
            "return arguments[0].textContent;",
            List.of(Map.of("element_handle", elementHandle)));
        return r == null ? "" : r.toString();
    }

    @Override
    public List<WebElement> findElements(By by) {
        String xpath = composeRelativeXpath(requireSourceXpath(), by);
        BrowsingClient browsingClient = requireClient("findElements");
        List<WebElement> matches = new ArrayList<>();
        for (int index = 1; index <= MAX_NESTED_FIND_ELEMENTS; index++) {
            String indexedXpath = "(" + xpath + ")[" + index + "]";
            ElementState state;
            try {
                state = browsingClient.findElement(sessionId, indexedXpath);
            } catch (BrowsingClientException exception) {
                if (isNotFound(exception)) {
                    break;
                }
                throw exception;
            }
            if (isNotFound(state)) {
                break;
            }
            matches.add(new RemoteWebElement(sessionId, indexedXpath, state, browsingClient));
        }
        return matches;
    }

    @Override
    public WebElement findElement(By by) {
        String xpath = composeRelativeXpath(requireSourceXpath(), by);
        ElementState state;
        try {
            state = requireClient("findElement").findElement(sessionId, xpath);
        } catch (BrowsingClientException exception) {
            if (!isNotFound(exception)) {
                throw exception;
            }
            throw new NoSuchElementException("No nested element found for xpath: " + xpath, exception);
        }
        if (isNotFound(state)) {
            throw new NoSuchElementException("No nested element found for xpath: " + xpath);
        }
        return new RemoteWebElement(sessionId, xpath, state, client);
    }

    @Override
    public String getCssValue(String propertyName) {
        Object r = requireClient("getCssValue").executeScript(sessionId,
            "return window.getComputedStyle(arguments[0]).getPropertyValue(arguments[1]);",
            List.of(Map.of("element_handle", elementHandle), propertyName));
        return r == null ? "" : r.toString();
    }
    @Override
    @SuppressWarnings("unchecked")
    public <X> X getScreenshotAs(OutputType<X> outputType) {
        if (outputType == OutputType.BYTES) {
            return (X) requireClient("getScreenshotAs").captureElementScreenshot(sessionId, elementHandle);
        }
        // BASE64 / FILE / others: real Look-see consumers don't use them.
        // Convert client-side from BYTES if needed (BASE64 = one Base64
        // encoder call; FILE = one Files.write).
        throw new UnsupportedOperationException(
            "RemoteWebElement.getScreenshotAs: only OutputType.BYTES is supported "
            + "in remote mode (got " + outputType + "). Convert client-side if "
            + "BASE64 or FILE is needed.");
    }

    // --- Identity --------------------------------------------------------

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RemoteWebElement)) return false;
        RemoteWebElement that = (RemoteWebElement) o;
        return sessionId.equals(that.sessionId) && elementHandle.equals(that.elementHandle);
    }

    @Override public int hashCode() { return Objects.hash(sessionId, elementHandle); }

    @Override public String toString() {
        return "RemoteWebElement{session=" + sessionId + ", handle=" + elementHandle + "}";
    }
}
