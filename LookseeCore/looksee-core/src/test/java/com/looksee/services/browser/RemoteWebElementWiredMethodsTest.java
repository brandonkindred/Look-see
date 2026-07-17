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
        // Nested finds re-resolve the parent source xpath first.
        when(client.findElement("s1", "//button")).thenReturn(s);
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
        verify(client).findElement("s1", "//button");
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
        when(client.findElement("s1", "//button"))
            .thenReturn(new ElementState()
                .elementHandle("other-form").found(true).displayed(true).attributes(Map.of()));

        assertThrows(org.openqa.selenium.StaleElementReferenceException.class,
            () -> el.findElement(By.xpath("./input")));
        verify(client, never()).findElement(eq("s1"), startsWith("((//button)"));
    }

    @Test
    void findElements_propagatesSessionNotFoundInsteadOfTruncating() {
        ElementState first = new ElementState()
            .elementHandle("tr-1").found(true).displayed(true).attributes(Map.of());
        when(client.findElement("s1", "((//button)//tr)[1]")).thenReturn(first);
        when(client.findElement("s1", "((//button)//tr)[2]"))
            .thenThrow(new BrowsingClientException("findElement failed",
                new ApiException(404, "session not found")));

        BrowsingClientException ex = assertThrows(BrowsingClientException.class,
            () -> el.findElements(By.tagName("tr")));
        assertTrue(ex.getCause() instanceof ApiException);
        assertEquals(404, ((ApiException) ex.getCause()).getCode());
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
    void getText_returnsTextContent() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn("Hello, world");
        assertEquals("Hello, world", el.getText());
    }

    @Test
    void getText_returnsEmptyStringOnNullResult() {
        when(client.executeScript(eq("s1"), any(), any())).thenReturn(null);
        assertEquals("", el.getText());
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
