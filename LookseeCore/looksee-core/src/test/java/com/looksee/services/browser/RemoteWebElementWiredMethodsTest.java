package com.looksee.services.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.client.BrowsingClientException;
import com.looksee.browsing.generated.ApiException;
import com.looksee.browsing.generated.model.ElementAction;
import com.looksee.browsing.generated.model.ElementState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;

/**
 * RemoteWebElement methods that use the BrowsingClient facade. These tests
 * verify client routing — nested xpath lookup, script content, enum
 * translation, output-type handling, etc.
 */
class RemoteWebElementWiredMethodsTest {

    private BrowsingClient client;
    private RemoteWebElement el;

    @BeforeEach
    void setUp() {
        client = mock(BrowsingClient.class);
        ElementState s = new ElementState()
            .elementHandle("h1").found(true).displayed(true).attributes(Map.of());
        el = new RemoteWebElement("s1", "//button", s, client);
        // Nested finds confirm parent DOM identity via executeScript (not handle equality).
        when(client.executeScript(eq("s1"), argThat(script ->
                script != null && script.contains("document.evaluate")), any()))
            .thenReturn(Boolean.TRUE);
    }

    // --- nested findElement(s) → /element/find ----------------------------

    @Test
    void findElement_composesRelativeXpathAndReturnsWiredChild() {
        ElementState child = new ElementState()
            .elementHandle("h2").found(true).displayed(true).attributes(Map.of("id", "name"));
        when(client.findElement("s1", "((//button)/input)[1]")).thenReturn(child);

        RemoteWebElement result = (RemoteWebElement) el.findElement(By.xpath("./input"));

        assertEquals("h2", result.getElementHandle());
        assertEquals("((//button)/input)[1]", result.getSourceXpath());
        verify(client).executeScript(eq("s1"), argThat(script ->
                script != null && script.contains("document.evaluate")), any());
        verify(client).findElement("s1", "((//button)/input)[1]");
    }

    @Test
    void findElements_enumeratesIndexedXpathsUntilNoMatch() {
        ElementState first = new ElementState()
            .elementHandle("tr-1").found(true).displayed(true).attributes(Map.of());
        ElementState second = new ElementState()
            .elementHandle("tr-2").found(true).displayed(true).attributes(Map.of());
        when(client.findElement("s1", "((//button)//tr)[1]")).thenReturn(first);
        when(client.findElement("s1", "((//button)//tr)[2]")).thenReturn(second);
        when(client.findElement("s1", "((//button)//tr)[3]")).thenReturn(new ElementState().found(false));

        List<WebElement> results = el.findElements(By.tagName("tr"));

        assertEquals(2, results.size());
        assertEquals("((//button)//tr)[1]", ((RemoteWebElement) results.get(0)).getSourceXpath());
        assertEquals("((//button)//tr)[2]", ((RemoteWebElement) results.get(1)).getSourceXpath());
        verify(client).findElement("s1", "((//button)//tr)[3]");
    }

    @Test
    void findElement_throwsNoSuchElementWhenResponseIsNotFound() {
        when(client.findElement("s1", "((//button)/input)[1]")).thenReturn(new ElementState().found(false));

        assertThrows(NoSuchElementException.class, () -> el.findElement(By.xpath("./input")));
    }

    @Test
    void findElement_throwsStaleWhenParentSourceXpathDrifted() {
        when(client.executeScript(eq("s1"), argThat(script ->
                script != null && script.contains("document.evaluate")), any()))
            .thenReturn(Boolean.FALSE);

        assertThrows(org.openqa.selenium.StaleElementReferenceException.class,
            () -> el.findElement(By.xpath("./input")));
        verify(client, never()).findElement(eq("s1"), startsWith("((//button)"));
    }

    @Test
    void findElement_propagatesBrowsingClientExceptionFromBindCheck() {
        when(client.executeScript(eq("s1"), argThat(script ->
                script != null && script.contains("document.evaluate")), any()))
            .thenThrow(new BrowsingClientException("executeScript failed",
                new ApiException(503, "unavailable")));

        assertThrows(BrowsingClientException.class,
            () -> el.findElement(By.xpath("./input")));
        verify(client, never()).findElement(eq("s1"), startsWith("((//button)"));
    }

    @Test
    void isLegacyElementMiss404_ignoresRequestContextInWrapperMessage() {
        // BrowsingClient embeds session + xpath in the wrapper message; locator
        // text must not flip a generic proxy 404 into a legacy element miss.
        BrowsingClientException ex = new BrowsingClientException(
            "findElement failed: s1 //*[text()='element not found']",
            new ApiException(404, "Not Found"));
        assertFalse(RemoteWebElement.isLegacyElementMiss404(ex));
    }

    @Test
    void isLegacyElementMiss404_acceptsStructuredApiElementMiss() {
        BrowsingClientException ex = new BrowsingClientException(
            "findElement failed: s1 //foo",
            new ApiException(404, "Not Found", null,
                "{\"error\":{\"code\":\"element_not_found\"}}"));
        assertTrue(RemoteWebElement.isLegacyElementMiss404(ex));
    }

    @Test
    void findElements_propagatesSessionNotFoundInsteadOfTruncating() {
        ElementState first = new ElementState()
            .elementHandle("tr-1").found(true).displayed(true).attributes(Map.of());
        when(client.findElement("s1", "((//button)//tr)[1]")).thenReturn(first);
        when(client.findElement("s1", "((//button)//tr)[2]"))
            .thenThrow(new BrowsingClientException("findElement failed",
                new ApiException(404, "Not Found", null,
                    "{\"error\":{\"code\":\"session_not_found\",\"message\":\"gone\"}}")));

        BrowsingClientException ex = assertThrows(BrowsingClientException.class,
            () -> el.findElements(By.tagName("tr")));
        assertTrue(ex.getCause() instanceof ApiException);
        assertEquals(404, ((ApiException) ex.getCause()).getCode());
    }

    @Test
    void findElements_treatsLegacyElementMiss404AsEndOfEnumeration() {
        ElementState first = new ElementState()
            .elementHandle("tr-1").found(true).displayed(true).attributes(Map.of());
        when(client.findElement("s1", "((//button)//tr)[1]")).thenReturn(first);
        // Draft/older servers 404'd on xpath miss with an element-miss payload.
        when(client.findElement("s1", "((//button)//tr)[2]"))
            .thenThrow(new BrowsingClientException("findElement failed",
                new ApiException(404, "element not found")));

        List<WebElement> results = el.findElements(By.tagName("tr"));

        assertEquals(1, results.size());
        assertEquals("tr-1", ((RemoteWebElement) results.get(0)).getElementHandle());
    }

    @Test
    void findElements_propagatesUnclassified404InsteadOfTreatingAsMiss() {
        ElementState first = new ElementState()
            .elementHandle("tr-1").found(true).displayed(true).attributes(Map.of());
        when(client.findElement("s1", "((//button)//tr)[1]")).thenReturn(first);
        // Generic proxy/outage 404 — must not silently truncate the list.
        when(client.findElement("s1", "((//button)//tr)[2]"))
            .thenThrow(new BrowsingClientException("findElement failed",
                new ApiException(404, "Not Found")));

        assertThrows(BrowsingClientException.class, () -> el.findElements(By.tagName("tr")));
    }

    @Test
    void findElements_returnsAllMatchesWithoutArtificialCap() {
        ElementState found = new ElementState()
            .elementHandle("h").found(true).displayed(true).attributes(Map.of());
        when(client.findElement(eq("s1"), anyString())).thenReturn(found);
        when(client.findElement("s1", "//button"))
            .thenReturn(new ElementState()
                .elementHandle("h1").found(true).displayed(true).attributes(Map.of()));
        // 501 matches then miss — previously a hard 500 cap would have thrown.
        when(client.findElement("s1", "((//button)//tr)[502]"))
            .thenReturn(new ElementState().found(false));

        List<WebElement> results = el.findElements(By.tagName("tr"));

        assertEquals(501, results.size());
    }

    // --- click + sendKeys → /element/action -------------------------------

    @Test
    void click_routesPerformElementActionClick() {
        el.click();
        verify(client).performElementAction("s1", "h1", ElementAction.CLICK, null);
    }

    @Test
    void sendKeys_concatsArgsAndForwards() {
        el.sendKeys("hello", " ", "world");
        verify(client).performElementAction("s1", "h1", ElementAction.SEND_KEYS, "hello world");
    }

    @Test
    void sendKeys_nullArrayThrowsIllegalArgument() {
        // Selenium contract: null keys array throws. Don't silently no-op
        // (PR #54 review).
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> el.sendKeys((CharSequence[]) null));
        assertTrue(ex.getMessage().contains("not null"),
            "error should mention nulls being rejected: " + ex.getMessage());
        verify(client, never()).performElementAction(any(), any(), any(), any());
    }

    @Test
    void sendKeys_nullElementThrowsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> el.sendKeys("ok", null, "more"));
        assertTrue(ex.getMessage().contains("not null"),
            "error should mention nulls being rejected: " + ex.getMessage());
        verify(client, never()).performElementAction(any(), any(), any(), any());
    }

    // --- submit + clear → /execute ----------------------------------------

    @Test
    void submit_prefersRequestSubmitOverSubmit() {
        // PR #54 review: prefer form.requestSubmit() over form.submit() so
        // submit handlers fire and constraint validation runs. Falls back
        // to .submit() only if requestSubmit isn't available.
        el.submit();
        ArgumentCaptor<String> scriptCap = ArgumentCaptor.forClass(String.class);
        verify(client).executeScript(eq("s1"), scriptCap.capture(), any());
        String script = scriptCap.getValue();
        assertTrue(script.contains("requestSubmit"),
            "submit script must prefer requestSubmit() to fire submit events: " + script);
    }

    @Test
    void clear_setsValueAndDispatchesInputEvent() {
        el.clear();
        ArgumentCaptor<String> scriptCap = ArgumentCaptor.forClass(String.class);
        verify(client).executeScript(eq("s1"), scriptCap.capture(), any());
        String script = scriptCap.getValue();
        assertTrue(script.contains("el.value = ''"),
            "clear must set value to empty: " + script);
        assertTrue(script.contains("dispatchEvent(new Event('input'"),
            "clear must dispatch input event for framework parity: " + script);
    }

    // --- DOM property reads → /execute ------------------------------------

    @Test
    void getText_usesInnerTextOnlyAfterVisibilityCheck() {
        ArgumentCaptor<String> scriptCap = ArgumentCaptor.forClass(String.class);
        when(client.executeScript(eq("s1"), scriptCap.capture(), any())).thenReturn("Hello, world");

        assertEquals("Hello, world", el.getText());
        String script = scriptCap.getValue();
        assertTrue(script.contains("getComputedStyle"),
            "getText must gate on rendered visibility before reading text: " + script);
        assertTrue(script.contains("display === 'none'"),
            "getText must return '' for display:none (WebDriver semantics): " + script);
        assertTrue(script.contains("innerText"),
            "getText should prefer innerText for visible nodes: " + script);
        assertTrue(script.indexOf("getComputedStyle") < script.indexOf("innerText"),
            "visibility check must precede innerText (Chromium innerText falls back to textContent when hidden)");
    }

    @Test
    void getText_returnsEmptyStringOnNullResult() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(null);
        assertEquals("", el.getText());
    }

    @Test
    void findElements_propagatesNullElementStateInsteadOfTruncating() {
        ElementState first = new ElementState()
            .elementHandle("tr-1").found(true).displayed(true).attributes(Map.of());
        when(client.findElement("s1", "((//button)//tr)[1]")).thenReturn(first);
        when(client.findElement("s1", "((//button)//tr)[2]")).thenReturn(null);

        BrowsingClientException ex = assertThrows(BrowsingClientException.class,
            () -> el.findElements(By.tagName("tr")));
        assertTrue(ex.getMessage().contains("null ElementState"));
    }

    @Test
    void requireElementState_rejectsNull() {
        BrowsingClientException ex = assertThrows(BrowsingClientException.class,
            () -> RemoteWebElement.requireElementState(null));
        assertTrue(ex.getMessage().contains("null ElementState"));
    }

    @Test
    void findElements_throwsStaleWhenParentUnboundOnTerminalMiss() {
        when(client.findElement("s1", "((//button)//tr)[1]"))
            .thenReturn(new ElementState().found(false));
        // Pre-loop bind check succeeds; post-miss recheck sees the parent gone.
        when(client.executeScript(eq("s1"), argThat(script ->
                script != null && script.contains("document.evaluate")), any()))
            .thenReturn(Boolean.TRUE)
            .thenReturn(Boolean.FALSE);

        assertThrows(org.openqa.selenium.StaleElementReferenceException.class,
            () -> el.findElements(By.tagName("tr")));
    }

    @Test
    void findElement_throwsStaleWhenParentUnboundOnChildMiss() {
        when(client.findElement("s1", "((//button)/input)[1]"))
            .thenReturn(new ElementState().found(false));
        when(client.executeScript(eq("s1"), argThat(script ->
                script != null && script.contains("document.evaluate")), any()))
            .thenReturn(Boolean.TRUE)
            .thenReturn(Boolean.FALSE);

        assertThrows(org.openqa.selenium.StaleElementReferenceException.class,
            () -> el.findElement(By.xpath("./input")));
    }

    @Test
    void isSelected_trueWhenServerReturnsTrue() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(Boolean.TRUE);
        assertTrue(el.isSelected());
    }

    @Test
    void isSelected_falseWhenServerReturnsFalseOrNull() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(Boolean.FALSE);
        assertFalse(el.isSelected());
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(null);
        assertFalse(el.isSelected());
    }

    @Test
    void isEnabled_trueWhenServerReturnsTrue() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(Boolean.TRUE);
        assertTrue(el.isEnabled());
    }

    @Test
    void isEnabled_defaultsTrueOnNullResult() {
        // Selenium convention: assume enabled when uncertain.
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(null);
        assertTrue(el.isEnabled());
    }

    @Test
    void isEnabled_falseOnlyWhenServerReturnsExplicitFalse() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(Boolean.FALSE);
        assertFalse(el.isEnabled());
    }

    @Test
    void getCssValue_passesPropertyNameAsArg() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn("rgb(0, 0, 0)");
        ArgumentCaptor<List<Object>> argsCap = ArgumentCaptor.forClass(List.class);
        el.getCssValue("color");
        verify(client).executeScript(eq("s1"), any(), argsCap.capture());
        // args = [{element_handle: h1}, "color"]
        assertEquals(2, argsCap.getValue().size());
        assertEquals("color", argsCap.getValue().get(1));
    }

    @Test
    void getCssValue_returnsEmptyStringOnNull() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(null);
        assertEquals("", el.getCssValue("display"));
    }

    // --- getScreenshotAs --------------------------------------------------

    @Test
    void getScreenshotAs_BYTES_returnsBytes() {
        byte[] bytes = new byte[] {1, 2, 3};
        when(client.captureElementScreenshot("s1", "h1")).thenReturn(bytes);
        assertArrayEquals(bytes, el.getScreenshotAs(OutputType.BYTES));
    }

    @Test
    void getScreenshotAs_BASE64_throwsWithPointer() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
            () -> el.getScreenshotAs(OutputType.BASE64));
        assertTrue(ex.getMessage().contains("BYTES"),
            "error should mention BYTES is the supported alternative: " + ex.getMessage());
        verify(client, never()).captureElementScreenshot(any(), any());
    }

    @Test
    void getScreenshotAs_FILE_throwsWithPointer() {
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
            () -> el.getScreenshotAs(OutputType.FILE));
        assertTrue(ex.getMessage().contains("BYTES"),
            "error should mention BYTES is the supported alternative: " + ex.getMessage());
    }
}
