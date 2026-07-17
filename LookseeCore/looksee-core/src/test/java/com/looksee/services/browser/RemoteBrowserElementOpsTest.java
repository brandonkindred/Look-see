package com.looksee.services.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.generated.model.AlertChoice;
import com.looksee.browsing.generated.model.AlertState;
import com.looksee.browsing.generated.model.DomRemovePreset;
import com.looksee.browsing.generated.model.ElementAction;
import com.looksee.browsing.generated.model.ElementState;
import com.looksee.browsing.generated.model.Rect;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Alert;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebElement;

/**
 * Covers every phase-3b RemoteBrowser override: correct facade method called,
 * correct enum translation, correct element_handle forwarded. Also verifies
 * the key invariant that {@code extractAttributes} and {@code isDisplayed}
 * serve from RemoteWebElement's cache without additional network calls.
 */
class RemoteBrowserElementOpsTest {

    private BrowsingClient client;
    private RemoteBrowser remote;

    @BeforeEach
    void setUp() {
        client = mock(BrowsingClient.class);
        remote = new RemoteBrowser(client, "session-1", "chrome");
    }

    private static ElementState state(String handle) {
        return state(handle, true, Map.of(), null);
    }

    private static ElementState state(String handle, boolean displayed,
                                      Map<String, String> attrs, Rect rect) {
        ElementState s = new ElementState().elementHandle(handle).found(true).displayed(displayed).attributes(attrs);
        if (rect != null) s.setRect(rect);
        return s;
    }

    // --- find + attribute ops ---------------------------------------------

    @Test
    void findElement_returnsRemoteWebElement() {
        when(client.findElement("session-1", "(//button)[1]")).thenReturn(state("h1"));
        WebElement el = remote.findElement("//button");
        assertTrue(el instanceof RemoteWebElement);
        assertEquals("h1", ((RemoteWebElement) el).getElementHandle());
    }

    @Test
    void findElement_throwsWhenNotFound() {
        when(client.findElement(any(), any()))
            .thenReturn(new ElementState().found(false).elementHandle("placeholder"));
        assertThrows(NoSuchElementException.class, () -> remote.findElement("//missing"));
    }

    @Test
    void findWebElementByXpath_delegatesToFindElement() {
        when(client.findElement("session-1", "(//x)[1]")).thenReturn(state("h2"));
        WebElement el = remote.findWebElementByXpath("//x");
        assertEquals("h2", ((RemoteWebElement) el).getElementHandle());
    }

    @Test
    void isDisplayed_usesFindElementResponseAndNoExtraCall() {
        when(client.findElement("session-1", "//p"))
            .thenReturn(state("h1", true, Map.of(), null));
        assertTrue(remote.isDisplayed("//p"));
        verify(client, times(1)).findElement(any(), any());
    }

    @Test
    void extractAttributes_readsCache_noNetworkCall() {
        RemoteWebElement el = new RemoteWebElement("session-1",
            state("h1", true, Map.of("id", "banner"), null));
        Map<String, String> result = remote.extractAttributes(el);
        assertEquals("banner", result.get("id"));
        verifyNoInteractions(client); // key invariant
    }

    @Test
    void extractAttributes_rejectsNonRemoteElement() {
        WebElement plainMock = mock(WebElement.class);
        assertThrows(IllegalStateException.class, () -> remote.extractAttributes(plainMock));
    }

    @Test
    void getElementScreenshot_decodesBytes() throws Exception {
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));
        when(client.captureElementScreenshot("session-1", "h1")).thenReturn(pngBytes(4, 3));
        BufferedImage img = remote.getElementScreenshot(el);
        assertEquals(4, img.getWidth());
        assertEquals(3, img.getHeight());
    }

    // --- scroll ops --------------------------------------------------------

    @Test
    void scrollToElementCentered_forwardsHandle() {
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));
        remote.scrollToElementCentered(el);
        verify(client).scrollToElementCentered("session-1", "h1");
    }

    @Test
    void scrollToElement_singleArg_routesToCentered() {
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));
        remote.scrollToElement(el);
        verify(client).scrollToElementCentered("session-1", "h1");
    }

    @Test
    void scrollToElement_xpathArg_passesHint() {
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));
        remote.scrollToElement("//body/nav", el);
        verify(client).scrollToElement("session-1", "h1", "//body/nav");
    }

    @Test
    void scrollToTopOfPage_forwards() {
        remote.scrollToTopOfPage();
        verify(client).scrollToTop("session-1");
    }

    @Test
    void scrollToBottomOfPage_forwards() {
        remote.scrollToBottomOfPage();
        verify(client).scrollToBottom("session-1");
    }

    @Test
    void scrollDownPercent_forwardsValue() {
        remote.scrollDownPercent(0.5);
        verify(client).scrollDownPercent(eq("session-1"), anyDouble());
    }

    @Test
    void scrollDownFull_forwards() {
        remote.scrollDownFull();
        verify(client).scrollDownFull("session-1");
    }

    // --- dom removal -------------------------------------------------------

    @Test
    void removeElement_byClass() {
        remote.removeElement("banner");
        verify(client).removeDomElement("session-1", DomRemovePreset.BY_CLASS, "banner");
    }

    @Test
    void removeDriftChat_usesDriftChatPreset() {
        remote.removeDriftChat();
        verify(client).removeDomElement("session-1", DomRemovePreset.DRIFT_CHAT, null);
    }

    @Test
    void removeGDPRmodals_usesGdprModalPreset() {
        remote.removeGDPRmodals();
        verify(client).removeDomElement("session-1", DomRemovePreset.GDPR_MODAL, null);
    }

    @Test
    void removeGDPR_usesGdprPreset() {
        remote.removeGDPR();
        verify(client).removeDomElement("session-1", DomRemovePreset.GDPR, null);
    }

    // --- mouse -------------------------------------------------------------

    @Test
    void moveMouseOutOfFrame_forwards() {
        remote.moveMouseOutOfFrame();
        verify(client).moveMouseOutOfFrame("session-1");
    }

    @Test
    void moveMouseOutOfFrame_swallowsExceptions() {
        doThrow(new com.looksee.browsing.client.BrowsingClientException("transient", null))
            .when(client).moveMouseOutOfFrame("session-1");
        assertDoesNotThrow(() -> remote.moveMouseOutOfFrame());
    }

    @Test
    void moveMouseToNonInteractive_forwardsCoords() {
        remote.moveMouseToNonInteractive(new Point(10, 20));
        verify(client).moveMouseToNonInteractive("session-1", 10, 20);
    }

    // --- alert -------------------------------------------------------------

    @Test
    void isAlertPresent_returnsNullWhenAbsent() {
        when(client.getAlert("session-1")).thenReturn(new AlertState().present(false));
        assertNull(remote.isAlertPresent());
    }

    @Test
    void isAlertPresent_returnsRemoteAlertWhenPresent() {
        when(client.getAlert("session-1"))
            .thenReturn(new AlertState().present(true).text("Are you sure?"));
        Alert alert = remote.isAlertPresent();
        assertNotNull(alert);
        assertTrue(alert instanceof RemoteAlert);
        assertEquals("Are you sure?", alert.getText());
    }

    // --- high-level Browser ops (phase-3b additions) -----------------------

    @Test
    void performClick_forwardsClickAction() {
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));
        remote.performClick(el);
        verify(client).performElementAction("session-1", "h1", ElementAction.CLICK, null);
    }

    @Test
    void performAction_translatesEnumAndForwardsInput() {
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));
        remote.performAction(el, com.looksee.browser.enums.Action.SEND_KEYS, "hello");
        verify(client).performElementAction("session-1", "h1", ElementAction.SEND_KEYS, "hello");
    }

    @Test
    void performAction_nullInputBecomesEmpty() {
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));
        remote.performAction(el, com.looksee.browser.enums.Action.CLICK, null);
        verify(client).performElementAction("session-1", "h1", ElementAction.CLICK, "");
    }

    @Test
    void getTitle_parsesFromSourceViaJsoup() {
        when(client.getSource("session-1")).thenReturn(
            "<!doctype html><html><head><title>Hello World</title></head><body/></html>");
        assertEquals("Hello World", remote.getTitle());
    }

    @Test
    void getTitle_emptySource_returnsEmptyString() {
        when(client.getSource("session-1")).thenReturn("");
        assertEquals("", remote.getTitle());
    }

    @Test
    void getCurrentUrl_readsFromPageStatus() {
        // PageStatus.current_url is required in the OpenAPI contract (unlike
        // SessionState.current_url which is optional) — protects journey-loop
        // .equals chains from NPE when the session has just been created.
        when(client.getStatus("session-1"))
            .thenReturn(new com.looksee.browsing.generated.model.PageStatus()
                .currentUrl("https://example.com/page").is503(false));
        assertEquals("https://example.com/page", remote.getCurrentUrl());
    }

    @Test
    void crossSessionElement_rejectedByRequireRemote() {
        RemoteWebElement fromOtherSession = new RemoteWebElement("other-session",
            state("h1"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> remote.performClick(fromOtherSession));
        assertTrue(ex.getMessage().contains("session"),
            "error should mention the session mismatch: " + ex.getMessage());
        verify(client, never()).performElementAction(any(), any(), any(), any());
    }

    // --- phase 3e additions: waitForElementClickable + getComputedCssProperties ----

    @Test
    void waitForElementClickable_returnsImmediatelyWhenDisplayed() {
        ElementState found = new ElementState().elementHandle("h1").found(true).displayed(true).attributes(java.util.Map.of());
        when(client.findElement("session-1", "//button")).thenReturn(found);
        RemoteWebElement el = new RemoteWebElement("session-1", "//button", found);

        assertDoesNotThrow(() -> remote.waitForElementClickable(el, java.time.Duration.ofSeconds(1)));
        verify(client, atLeastOnce()).findElement("session-1", "//button");
    }

    @Test
    void waitForElementClickable_pollsUntilDisplayed() {
        ElementState notReady = new ElementState().elementHandle("h1").found(true).displayed(false).attributes(java.util.Map.of());
        ElementState ready = new ElementState().elementHandle("h1").found(true).displayed(true).attributes(java.util.Map.of());
        when(client.findElement("session-1", "//slow"))
            .thenReturn(notReady, notReady, ready);
        RemoteWebElement el = new RemoteWebElement("session-1", "//slow",
            new ElementState().elementHandle("h1").found(true).displayed(false).attributes(java.util.Map.of()));

        assertDoesNotThrow(() -> remote.waitForElementClickable(el, java.time.Duration.ofSeconds(2)));
        verify(client, atLeast(2)).findElement("session-1", "//slow");
    }

    @Test
    void waitForElementClickable_throwsOnTimeout() {
        ElementState notReady = new ElementState().elementHandle("h1").found(true).displayed(false).attributes(java.util.Map.of());
        when(client.findElement("session-1", "//never")).thenReturn(notReady);
        RemoteWebElement el = new RemoteWebElement("session-1", "//never", notReady);

        // 0-second timeout — the post-loop check after the first findElement
        // reads "not yet" + deadline already passed, throws.
        assertThrows(org.openqa.selenium.TimeoutException.class,
            () -> remote.waitForElementClickable(el, java.time.Duration.ofMillis(1)));
    }

    @Test
    void waitForElementClickable_fallsBackWhenNoSourceXpath() {
        // Backward-compat: if the RemoteWebElement was constructed without
        // an xpath (legacy 2-arg constructor), the override can't poll —
        // it does a single cached-displayed check and returns or throws.
        RemoteWebElement displayed = new RemoteWebElement("session-1", state("h1"));
        assertDoesNotThrow(() -> remote.waitForElementClickable(displayed, java.time.Duration.ofMillis(1)));
        verify(client, never()).findElement(any(), any());
    }

    @Test
    void getComputedCssProperties_invokesExecuteScriptAndConvertsResult() {
        when(client.executeScript(eq("session-1"), any(), any()))
            .thenReturn(java.util.Map.of("color", "rgb(0, 0, 0)", "display", "block"));
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));

        java.util.Map<String, String> css = remote.getComputedCssProperties(el);

        assertEquals("rgb(0, 0, 0)", css.get("color"));
        assertEquals("block", css.get("display"));

        org.mockito.ArgumentCaptor<String> scriptCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(client).executeScript(eq("session-1"), scriptCap.capture(), any());
        assertTrue(scriptCap.getValue().contains("getComputedStyle"),
            "remote getComputedCssProperties must run getComputedStyle JS");
    }

    @Test
    void getComputedCssProperties_returnsEmptyOnNonMapResult() {
        when(client.executeScript(any(), any(), any())).thenReturn("not a map");
        RemoteWebElement el = new RemoteWebElement("session-1", state("h1"));
        assertTrue(remote.getComputedCssProperties(el).isEmpty());
    }

    @Test
    void findElement_returnedElementCarriesBrowsingClient() {
        // Phase 3f end-to-end smoke: an element from remote.findElement
        // must carry the BrowsingClient reference, so its WebElement-API
        // methods (click, sendKeys, etc.) route correctly without needing
        // a parent RemoteBrowser. Verifies the client passthrough in
        // RemoteBrowser.findElement.
        when(client.findElement("session-1", "(//button)[1]"))
            .thenReturn(new com.looksee.browsing.generated.model.ElementState()
                .elementHandle("h-found").found(true).displayed(true).attributes(java.util.Map.of()));

        org.openqa.selenium.WebElement found = remote.findElement("//button");
        assertTrue(found instanceof RemoteWebElement);
        // If the client wasn't wired, click() would throw with "constructed
        // without BrowsingClient". A successful routing call confirms the
        // passthrough worked.
        found.click();
        verify(client).performElementAction("session-1", "h-found",
            com.looksee.browsing.generated.model.ElementAction.CLICK, null);
    }

    // --- helper ------------------------------------------------------------

    private static byte[] pngBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
