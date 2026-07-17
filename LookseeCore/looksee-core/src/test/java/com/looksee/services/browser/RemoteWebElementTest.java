package com.looksee.services.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.generated.model.ElementState;
import com.looksee.browsing.generated.model.Rect;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;

class RemoteWebElementTest {

    private static ElementState state(String handle, boolean displayed, Map<String, String> attrs, Rect rect) {
        ElementState s = new ElementState()
            .elementHandle(handle)
            .found(true)
            .displayed(displayed)
            .attributes(attrs);
        if (rect != null) {
            s.setRect(rect);
        }
        return s;
    }

    @Test
    void isDisplayed_servedFromCacheWithoutNetwork() {
        RemoteWebElement el = new RemoteWebElement("s1",
            state("h1", true, Map.of(), null));
        assertTrue(el.isDisplayed());
    }

    @Test
    void getLocationAndSize_derivedFromRect() {
        RemoteWebElement el = new RemoteWebElement("s1",
            state("h1", true, Map.of(),
                new Rect().x(10).y(20).width(100).height(50)));
        assertEquals(10, el.getLocation().getX());
        assertEquals(20, el.getLocation().getY());
        assertEquals(100, el.getSize().getWidth());
        assertEquals(50, el.getSize().getHeight());
    }

    @Test
    void getRect_composesLocationAndSize() {
        RemoteWebElement el = new RemoteWebElement("s1",
            state("h1", true, Map.of(),
                new Rect().x(1).y(2).width(3).height(4)));
        assertEquals(1, el.getRect().getX());
        assertEquals(2, el.getRect().getY());
        assertEquals(3, el.getRect().getWidth());
        assertEquals(4, el.getRect().getHeight());
    }

    @Test
    void missingRect_returnsZeroLocation() {
        RemoteWebElement el = new RemoteWebElement("s1", state("h1", true, Map.of(), null));
        assertEquals(0, el.getLocation().getX());
        assertEquals(0, el.getSize().getWidth());
    }

    @Test
    void getAttribute_readsCachedMap() {
        RemoteWebElement el = new RemoteWebElement("s1",
            state("h1", true, Map.of("id", "submit", "class", "btn primary"), null));
        assertEquals("submit", el.getAttribute("id"));
        assertEquals("btn primary", el.getAttribute("class"));
        // No client → missing keys stay null (no executeScript fallback).
        assertNull(el.getAttribute("missing"));
    }

    @Test
    void getAttribute_fetchesDomPropertiesViaExecuteScriptWhenMissingFromCache() {
        BrowsingClient client = mock(BrowsingClient.class);
        RemoteWebElement el = new RemoteWebElement(
            "s1", "//form", state("h1", true, Map.of("id", "contact"), null), client);
        when(client.executeScript(eq("s1"), anyString(), any())).thenAnswer(invocation -> {
            java.util.List<?> args = invocation.getArgument(2);
            String name = args.get(1).toString();
            if ("innerHTML".equals(name)) return "<input>";
            if ("outerHTML".equals(name)) return "<form id=\"contact\"><input></form>";
            return null;
        });

        assertEquals("<input>", el.getAttribute("innerHTML"));
        assertEquals("<form id=\"contact\"><input></form>", el.getAttribute("outerHTML"));

        clearInvocations(client);
        // Cached HTML attribute still served without a round-trip.
        assertEquals("contact", el.getAttribute("id"));
        verify(client, never()).executeScript(any(), any(), any());
    }

    @Test
    void nullAttributes_treatedAsEmpty() {
        RemoteWebElement el = new RemoteWebElement("s1",
            new ElementState().elementHandle("h1").found(true));
        assertNull(el.getAttribute("any"));
    }

    @Test
    void equality_sessionIdPlusElementHandle() {
        RemoteWebElement a = new RemoteWebElement("s1", state("h1", false, Map.of(), null));
        RemoteWebElement b = new RemoteWebElement("s1", state("h1", true, Map.of("x", "y"), null));
        RemoteWebElement c = new RemoteWebElement("s1", state("h2", false, Map.of(), null));
        RemoteWebElement d = new RemoteWebElement("s2", state("h1", false, Map.of(), null));

        assertEquals(a, b, "same sessionId + elementHandle → equal regardless of cached state");
        assertNotEquals(a, c, "different handle → not equal");
        assertNotEquals(a, d, "different sessionId → not equal");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void nestedFinds_requireSourceXpath() {
        RemoteWebElement el = new RemoteWebElement("s1", state("h1", true, Map.of(), null));

        Runnable[] checks = new Runnable[] {
            () -> el.findElements(By.xpath("./child")),
            () -> el.findElement(By.xpath("./child")),
        };
        for (Runnable r : checks) {
            UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, r::run);
            assertTrue(ex.getMessage().contains("source xpath"),
                "message should point at the missing source xpath: " + ex.getMessage());
        }
    }

    @Test
    void nestedFinds_rejectUnsupportedByTypes() {
        RemoteWebElement el = new RemoteWebElement("s1", "//form", state("h1", true, Map.of(), null),
            mock(BrowsingClient.class));

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
            () -> el.findElement(By.id("submit")));

        assertTrue(ex.getMessage().contains("unsupported By type"),
            "message should identify the unsupported locator: " + ex.getMessage());
    }

    @Test
    void nestedFinds_rejectAbsoluteXpaths() {
        RemoteWebElement el = new RemoteWebElement("s1", "//form", state("h1", true, Map.of(), null),
            mock(BrowsingClient.class));

        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class,
            () -> el.findElements(By.xpath("//tr")));

        assertTrue(ex.getMessage().contains("relative xpath"),
            "message should identify the relative xpath requirement: " + ex.getMessage());
    }

    @Test
    void clientLessConstruction_throwsForWebElementApiMethods() {
        // RemoteWebElement constructed via the legacy 2-arg or 3-arg form
        // (no BrowsingClient) can't route WebElement-API calls. Each method
        // throws with a clear pointer at construction-style ambiguity.
        RemoteWebElement el = new RemoteWebElement("s1", state("h1", true, Map.of(), null));
        for (Runnable r : new Runnable[] {
            () -> el.click(),
            () -> el.submit(),
            () -> el.sendKeys("x"),
            () -> el.clear(),
            () -> el.isSelected(),
            () -> el.isEnabled(),
            () -> el.getText(),
            () -> el.getCssValue("color"),
            () -> el.getScreenshotAs(OutputType.BYTES),
        }) {
            UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, r::run);
            assertTrue(ex.getMessage().contains("BrowsingClient"),
                "message should point at the missing client: " + ex.getMessage());
        }
    }

    @Test
    void getTagName_readsCachedTagNameAttribute() {
        // Phase 3d: when the server includes a synthetic tag_name in the
        // findElement response's attributes map, RemoteWebElement.getTagName
        // returns it without a round-trip.
        RemoteWebElement el = new RemoteWebElement("s1",
            state("h1", true, Map.of("tag_name", "div"), null));
        assertEquals("div", el.getTagName());
    }

    @Test
    void getTagName_throwsWhenAttributeAbsent() {
        // Browser-service today does NOT synthesize tag_name. Confirms the
        // throw still fires with an actionable message pointing at the
        // phase-3d xpath workaround.
        RemoteWebElement el = new RemoteWebElement("s1",
            state("h1", true, Map.of("id", "submit"), null));
        UnsupportedOperationException ex = assertThrows(
            UnsupportedOperationException.class, el::getTagName);
        assertTrue(ex.getMessage().contains("tag_name"),
            "message should mention the missing tag_name attribute: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("extractTagFromXpath"),
            "message should point at the workaround: " + ex.getMessage());
    }

    @Test
    void nullSessionId_throws() {
        assertThrows(NullPointerException.class,
            () -> new RemoteWebElement(null, state("h1", true, Map.of(), null)));
    }

    @Test
    void nullElementHandle_throws() {
        assertThrows(NullPointerException.class,
            () -> new RemoteWebElement("s1", new ElementState().found(true)));
    }
}
