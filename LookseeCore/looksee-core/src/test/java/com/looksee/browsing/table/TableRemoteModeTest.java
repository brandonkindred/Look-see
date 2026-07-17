package com.looksee.browsing.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.generated.model.ElementState;
import com.looksee.services.browser.RemoteWebElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Proves {@link Table#loadHeaders} can traverse header rows via nested
 * {@link RemoteWebElement#findElements} (Task 3 xpath composition).
 */
class TableRemoteModeTest {

    @Test
    void loadHeaders_invokesFindElementsOnRemoteWebElementWithoutThrowing() {
        BrowsingClient client = mock(BrowsingClient.class);
        ElementState thead = new ElementState()
            .elementHandle("thead-h")
            .found(true)
            .displayed(true)
            .attributes(Map.of());
        RemoteWebElement tableHeader = new RemoteWebElement(
            "s1",
            "//table/thead",
            thead,
            client);

        ElementState firstRow = new ElementState()
            .elementHandle("tr-1")
            .found(true)
            .displayed(true)
            .attributes(Map.of());
        ElementState secondRow = new ElementState()
            .elementHandle("tr-2")
            .found(true)
            .displayed(true)
            .attributes(Map.of());
        when(client.findElement("s1", "//table/thead")).thenReturn(thead);
        when(client.findElement("s1", "((//table/thead)/tr)[1]")).thenReturn(firstRow);
        when(client.findElement("s1", "((//table/thead)/tr)[2]")).thenReturn(secondRow);
        when(client.findElement("s1", "((//table/thead)/tr)[3]"))
            .thenReturn(new ElementState().found(false));

        Table table = new Table();
        WebDriver driver = mock(WebDriver.class);

        List<Row> rows = assertDoesNotThrow(() -> table.loadHeaders(tableHeader, driver));

        assertNotNull(rows);
        // Live loadHeaders path still returns empty rows; nested findElements must succeed.
        assertTrue(rows.isEmpty());
        verify(client).findElement("s1", "((//table/thead)/tr)[1]");
        verify(client).findElement("s1", "((//table/thead)/tr)[2]");
        verify(client).findElement("s1", "((//table/thead)/tr)[3]");
    }

    @Test
    void loadHeaders_acceptsRemoteWebElementAsWebElementInterface() {
        BrowsingClient client = mock(BrowsingClient.class);
        ElementState thead = new ElementState()
            .elementHandle("thead-h")
            .found(true)
            .displayed(true)
            .attributes(Map.of());
        WebElement tableHeader = new RemoteWebElement(
            "s1",
            "//table/thead",
            thead,
            client);
        when(client.findElement("s1", "//table/thead")).thenReturn(thead);
        when(client.findElement(eq("s1"), startsWith("((//table/thead)/tr)")))
            .thenReturn(new ElementState().found(false));

        Table table = new Table();
        assertDoesNotThrow(() -> table.loadHeaders(tableHeader, mock(WebDriver.class)));
    }
}
