package com.looksee.services.browser;

import com.looksee.browser.Browser;
import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.client.BrowsingClientException;
import com.looksee.browsing.generated.model.AlertState;
import com.looksee.browsing.generated.model.DomRemovePreset;
import com.looksee.browsing.generated.model.ElementState;
import com.looksee.browsing.generated.model.PageStatus;
import com.looksee.browsing.generated.model.ScreenshotStrategy;
import com.looksee.browsing.generated.model.ScrollOffset;
import com.looksee.browsing.generated.model.Viewport;
import com.looksee.browsing.generated.model.ViewportState;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openqa.selenium.Alert;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;

/**
 * {@link Browser} implementation that forwards page-level operations to
 * brandonkindred/browser-service via {@link BrowsingClient}.
 *
 * <p>This lives in {@code looksee-core} (not {@code looksee-browser}) so the
 * engine module stays pure-local. In remote-only deployments
 * {@code looksee-browser}'s Selenium transitive deps still load but no local
 * {@link WebDriver} is ever constructed — this class passes a null driver to
 * super and overrides every method that would otherwise dereference it.
 *
 * <p><b>Scope:</b> page-level ops only (navigate, screenshot, source, close,
 * status, viewport). Element-handle ops (findElement, extractAttributes,
 * removeDriftChat, scroll helpers, …) throw {@link UnsupportedOperationException}
 * — those are phase 3b. Consumers that exercise those paths must stay on
 * {@code looksee.browsing.mode=local} until 3b ships.
 */
public class RemoteBrowser extends Browser {

    private static final Logger log = LoggerFactory.getLogger(RemoteBrowser.class);
    private static final String PHASE_3B = "RemoteBrowser: element-handle ops are deferred to phase 3b";

    private final BrowsingClient client;
    private final String sessionId;
    private final String browserName;

    public RemoteBrowser(BrowsingClient client, String sessionId, String browserName) {
        super(); // Browser is @NoArgsConstructor; driver field stays null
        this.client = client;
        this.sessionId = sessionId;
        this.browserName = browserName;
    }

    public String getSessionId() {
        return sessionId;
    }

    // --- Page-level forwards ----------------------------------------------

    @Override
    public void navigateTo(String url) {
        client.navigate(sessionId, url);
    }

    @Override
    public void close() {
        // Mirror Browser.close() semantics: callers invoke this from finally
        // blocks, so a transient cleanup failure must not mask the original
        // exception or break retry/control flow. Log and move on.
        try {
            client.deleteSession(sessionId);
        } catch (BrowsingClientException e) {
            log.warn("RemoteBrowser.close: deleteSession({}) failed; swallowing", sessionId, e);
        }
    }

    @Override
    public String getSource() {
        return client.getSource(sessionId);
    }

    @Override
    public boolean is503Error() {
        PageStatus s = client.getStatus(sessionId);
        return Boolean.TRUE.equals(s.getIs503());
    }

    @Override
    public BufferedImage getViewportScreenshot() throws IOException {
        return readPng(client.screenshot(sessionId, ScreenshotStrategy.VIEWPORT));
    }

    @Override
    public BufferedImage getFullPageScreenshot() throws IOException {
        // No dedicated "plain full_page" strategy in the contract; use
        // shutterbug as the canonical full-page capture.
        return readPng(client.screenshot(sessionId, ScreenshotStrategy.FULL_PAGE_SHUTTERBUG));
    }

    @Override
    public BufferedImage getFullPageScreenshotAshot() throws IOException {
        return readPng(client.screenshot(sessionId, ScreenshotStrategy.FULL_PAGE_ASHOT));
    }

    @Override
    public BufferedImage getFullPageScreenshotShutterbug() throws IOException {
        return readPng(client.screenshot(sessionId, ScreenshotStrategy.FULL_PAGE_SHUTTERBUG));
    }

    @Override
    public Point getViewportScrollOffset() {
        ScrollOffset off = client.getViewport(sessionId).getScrollOffset();
        return new Point(off.getX(), off.getY());
    }

    @Override
    public void waitForPageToLoad() {
        // The service's /navigate endpoint blocks until document.readyState is
        // "complete"; no client-side wait needed. Left as an override so the
        // super-method (which dereferences the null driver) is never called.
    }

    // --- High-level Browser ops (phase-3b additions on Browser base) -----

    @Override
    public void performClick(WebElement element) {
        client.performElementAction(
            sessionId,
            requireRemote(element, "performClick").getElementHandle(),
            com.looksee.browsing.generated.model.ElementAction.CLICK,
            null);
    }

    @Override
    public void performAction(WebElement element,
                              com.looksee.browser.enums.Action action,
                              String input) {
        client.performElementAction(
            sessionId,
            requireRemote(element, "performAction").getElementHandle(),
            toGeneratedAction(action),
            input == null ? "" : input);
    }

    @Override
    public String getCurrentUrl() {
        // PageStatus.current_url is required in the OpenAPI contract (unlike
        // SessionState.current_url which is optional), so callers that chain
        // .equals / null-compare the result won't NPE. See
        // browser-service/phase-3b-element-handle-ops.md review on PR #38.
        return client.getStatus(sessionId).getCurrentUrl();
    }

    @Override
    public String getTitle() {
        // No dedicated /title endpoint in the OpenAPI spec; deriving the title
        // from getSource() avoids a contract change. Callers that need both
        // title and source should fetch source once and Jsoup-parse locally —
        // this method is the title-only convenience path used by
        // PageStateAdapter.toPageState(Browser, ...). See
        // browser-service/phase-3c-internal-remote-compat.md §Step 1.
        return org.jsoup.Jsoup.parse(client.getSource(sessionId)).title();
    }

    @Override
    public java.util.Map<String, String> getComputedCssProperties(org.openqa.selenium.WebElement element) {
        String handle = requireRemote(element, "getComputedCssProperties").getElementHandle();
        // Mirror what CssUtils.loadCssProperties extracts locally:
        // window.getComputedStyle over every property in the CSS declaration,
        // returned as { name: value }. The script is a literal — never
        // user-controlled — so there's no script-injection surface.
        String script =
            "var el = arguments[0]; "
            + "var s = window.getComputedStyle(el); "
            + "var out = {}; "
            + "for (var i = 0; i < s.length; i++) { "
            + "  var n = s.item(i); "
            + "  out[n] = s.getPropertyValue(n); "
            + "} "
            + "return out;";
        Object result = client.executeScript(sessionId, script,
            java.util.List.of(java.util.Map.of("element_handle", handle)));
        if (!(result instanceof java.util.Map)) {
            return java.util.Collections.emptyMap();
        }
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> raw = (java.util.Map<String, Object>) result;
        java.util.Map<String, String> out = new java.util.HashMap<>(raw.size());
        for (java.util.Map.Entry<String, Object> e : raw.entrySet()) {
            out.put(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
        }
        return out;
    }

    @Override
    public void waitForElementClickable(org.openqa.selenium.WebElement element,
                                        java.time.Duration timeout) {
        RemoteWebElement remote = requireRemote(element, "waitForElementClickable");
        String xpath = remote.getSourceXpath();
        if (xpath == null) {
            // Element wasn't constructed with a sourceXpath (e.g. came from a
            // pre-3e RemoteWebElement constructor). Fall back to a single
            // displayed-flag check from the cache; can't poll without an xpath.
            if (remote.isDisplayed()) return;
            throw new org.openqa.selenium.TimeoutException(
                "RemoteBrowser.waitForElementClickable: element " + remote.getElementHandle()
                + " has no sourceXpath cached and is not currently displayed");
        }
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        long pollMillis = 250;
        while (true) {
            try {
                ElementState fresh = client.findElement(sessionId, xpath);
                if (Boolean.TRUE.equals(fresh.getFound()) && Boolean.TRUE.equals(fresh.getDisplayed())) {
                    return;
                }
            } catch (com.looksee.browsing.client.BrowsingClientException e) {
                // Transient — keep polling until deadline.
                log.debug("waitForElementClickable: transient lookup error, retrying: {}", e.getMessage());
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new org.openqa.selenium.TimeoutException(
                    "RemoteBrowser.waitForElementClickable: element " + remote.getElementHandle()
                    + " (xpath=" + xpath + ") did not become clickable within " + timeout);
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new org.openqa.selenium.TimeoutException(
                    "RemoteBrowser.waitForElementClickable interrupted for element " + remote.getElementHandle());
            }
        }
    }

    private static com.looksee.browsing.generated.model.ElementAction toGeneratedAction(
            com.looksee.browser.enums.Action action) {
        // Both enums share lowercase wire values; see openapi.yaml and
        // com.looksee.browser.enums.Action.
        return com.looksee.browsing.generated.model.ElementAction.fromValue(action.toString());
    }

    // --- Lombok @Getter overrides (super reads the null driver) ----------

    @Override
    public WebDriver getDriver() {
        throw new UnsupportedOperationException(
            "RemoteBrowser has no local WebDriver — operate via BrowsingClient");
    }

    @Override
    public String getBrowserName() {
        return browserName;
    }

    @Override
    public Dimension getViewportSize() {
        Viewport v = client.getViewport(sessionId).getViewport();
        return new Dimension(v.getWidth(), v.getHeight());
    }

    @Override
    public long getXScrollOffset() {
        return client.getViewport(sessionId).getScrollOffset().getX();
    }

    @Override
    public long getYScrollOffset() {
        return client.getViewport(sessionId).getScrollOffset().getY();
    }

    // --- Element-handle ops -----------------------------------------------

    @Override
    public WebElement findElement(String xpath) throws WebDriverException {
        // Always resolve via an indexed locator in one round-trip so the
        // returned handle and source xpath describe the same DOM snapshot.
        // Nested finds then compose against that concrete node rather than
        // every sibling that matched the original non-unique xpath.
        String sourceXpath = RemoteWebElement.toIndexedSourceXpath(xpath);
        ElementState state = client.findElement(sessionId, sourceXpath);
        if (!Boolean.TRUE.equals(state.getFound())) {
            throw new NoSuchElementException(
                "RemoteBrowser: element not found for xpath=" + xpath);
        }
        // Pass the BrowsingClient through so the returned element can route
        // its own WebElement-API calls (phase 3f) without needing this
        // RemoteBrowser as a parent reference.
        return new RemoteWebElement(sessionId, sourceXpath, state, client);
    }

    @Override
    public List<WebElement> findElements(String xpath) throws WebDriverException {
        List<WebElement> matches = new ArrayList<>();
        // Enumerate until the first miss — Selenium returns the full set with no
        // artificial cap. Each indexed lookup is a separate round-trip; a stable
        // plural snapshot still needs a server-side findElements under the lock.
        for (int index = 1; ; index++) {
            String indexedXpath = "(" + xpath + ")[" + index + "]";
            // browser-service returns HTTP 200 + found=false for missing
            // elements; HTTP 404 means session expiry and must propagate.
            ElementState state = client.findElement(sessionId, indexedXpath);
            if (!Boolean.TRUE.equals(state.getFound())) {
                return matches;
            }
            matches.add(new RemoteWebElement(sessionId, indexedXpath, state, client));
        }
    }

    @Override
    public WebElement findWebElementByXpath(String xpath) {
        // Browser.findWebElementByXpath delegates to the same driver.findElement
        // under the hood; the remote mapping is identical.
        return findElement(xpath);
    }

    @Override
    public boolean isDisplayed(String xpath) {
        ElementState state = client.findElement(sessionId, xpath);
        return Boolean.TRUE.equals(state.getFound())
            && Boolean.TRUE.equals(state.getDisplayed());
    }

    @Override
    public Map<String, String> extractAttributes(WebElement element) {
        // Cached from the findElement response — no network call needed.
        return requireRemote(element, "extractAttributes").cachedAttributes();
    }

    @Override
    public BufferedImage getElementScreenshot(WebElement element) throws IOException {
        byte[] bytes = client.captureElementScreenshot(
            sessionId, requireRemote(element, "getElementScreenshot").getElementHandle());
        return readPng(bytes);
    }

    /**
     * Guards the contract that element-taking methods on {@link RemoteBrowser}
     * must be called with {@link RemoteWebElement} instances obtained from
     * **this** session. Passing a locally-bound {@link WebElement} or a
     * {@link RemoteWebElement} bound to a different session is a programming
     * error — in the cross-session case the handle would be forwarded to this
     * session's endpoints and either 404 or (worse) collide with a handle from
     * this session's namespace.
     */
    private RemoteWebElement requireRemote(WebElement element, String methodName) {
        if (!(element instanceof RemoteWebElement)) {
            throw new IllegalStateException(
                "RemoteBrowser." + methodName + ": element was not obtained from a RemoteBrowser session");
        }
        RemoteWebElement remote = (RemoteWebElement) element;
        if (!sessionId.equals(remote.getSessionId())) {
            throw new IllegalStateException(
                "RemoteBrowser." + methodName + ": element is bound to session "
                + remote.getSessionId() + " but this browser is session " + sessionId);
        }
        return remote;
    }

    // --- Scroll ops (ScrollMode enum maps 1:1 to Browser.java scroll methods) -

    @Override
    public void scrollToElement(String xpath, WebElement elem) {
        client.scrollToElement(sessionId, requireRemote(elem, "scrollToElement").getElementHandle(), xpath);
    }

    @Override
    public void scrollToElement(WebElement element) {
        // Browser.scrollToElement(WebElement) uses scrollIntoView({block:'center'})
        // server-side — route to TO_ELEMENT_CENTERED to match semantics.
        client.scrollToElementCentered(sessionId, requireRemote(element, "scrollToElement").getElementHandle());
    }

    @Override
    public void scrollToElementCentered(WebElement element) {
        client.scrollToElementCentered(sessionId, requireRemote(element, "scrollToElementCentered").getElementHandle());
    }

    @Override
    public void scrollToBottomOfPage() {
        client.scrollToBottom(sessionId);
    }

    @Override
    public void scrollToTopOfPage() {
        client.scrollToTop(sessionId);
    }

    @Override
    public void scrollDownPercent(double percent) {
        client.scrollDownPercent(sessionId, percent);
    }

    @Override
    public void scrollDownFull() {
        client.scrollDownFull(sessionId);
    }

    // --- DOM removal (DomRemovePreset enum) -------------------------------

    @Override
    public void removeElement(String className) {
        client.removeDomElement(sessionId, DomRemovePreset.BY_CLASS, className);
    }

    @Override
    public void removeDriftChat() {
        client.removeDomElement(sessionId, DomRemovePreset.DRIFT_CHAT, null);
    }

    @Override
    public void removeGDPRmodals() {
        client.removeDomElement(sessionId, DomRemovePreset.GDPR_MODAL, null);
    }

    @Override
    public void removeGDPR() {
        client.removeDomElement(sessionId, DomRemovePreset.GDPR, null);
    }

    // --- Mouse (MouseMoveMode enum) ---------------------------------------

    @Override
    public void moveMouseOutOfFrame() {
        // Local Browser.moveMouseOutOfFrame swallows exceptions; mirror that.
        try {
            client.moveMouseOutOfFrame(sessionId);
        } catch (BrowsingClientException e) {
            log.debug("RemoteBrowser.moveMouseOutOfFrame: swallowed {}", e.getMessage());
        }
    }

    @Override
    public void moveMouseToNonInteractive(Point point) {
        // Local Browser.moveMouseToNonInteractive swallows exceptions; mirror that.
        try {
            client.moveMouseToNonInteractive(sessionId, point.getX(), point.getY());
        } catch (BrowsingClientException e) {
            log.debug("RemoteBrowser.moveMouseToNonInteractive: swallowed {}", e.getMessage());
        }
    }

    // --- Alert ------------------------------------------------------------

    @Override
    public Alert isAlertPresent() {
        AlertState state = client.getAlert(sessionId);
        if (!Boolean.TRUE.equals(state.getPresent())) {
            return null;
        }
        return new RemoteAlert(client, sessionId, state.getText());
    }

    // --- helpers ----------------------------------------------------------

    private static BufferedImage readPng(byte[] bytes) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        }
    }
}
