package com.looksee.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.looksee.browser.Browser;
import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.generated.model.ElementState;
import com.looksee.browsing.generated.model.Rect;
import com.looksee.models.Domain;
import com.looksee.services.browser.RemoteBrowser;
import com.looksee.services.browser.RemoteWebElement;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BrowserServiceExtractAllFormsRemoteTest {

    @Test
    void extractAllFormsDoesNotCallGetDriver() throws Exception {
        Browser browser = mock(RemoteBrowser.class);
        Domain domain = mock(Domain.class);
        BrowserService browserService = new BrowserService();
        when(browser.getCurrentUrl()).thenReturn("https://example.com/form");
        when(browser.findElements("//form")).thenReturn(Collections.emptyList());

        assertNotNull(browserService.extractAllForms(1L, domain, browser));

        verify(browser, never()).getDriver();
    }

    @Test
    void extractAllFormsBuildsDisplayedRemoteFormWithoutTagNameAttribute() throws Exception {
        Browser browser = mock(RemoteBrowser.class);
        Domain domain = mock(Domain.class);
        BrowsingClient client = mock(BrowsingClient.class);
        ElementService elementService = mock(ElementService.class);
        ElementState state = new ElementState()
            .elementHandle("form-1")
            .found(true)
            .displayed(true)
            .attributes(Map.of(
                "id", "contact-form",
                "innerHTML", "<input name=\"email\">",
                "outerHTML", "<form id=\"contact-form\"><input name=\"email\"></form>"))
            .rect(new Rect().x(10).y(20).width(100).height(50));
        RemoteWebElement form = new RemoteWebElement("session-1", "//form[1]", state, client);
        BrowserService browserService = new BrowserService();
        ReflectionTestUtils.setField(browserService, "element_service", elementService);

        when(browser.getCurrentUrl()).thenReturn("https://example.com/form");
        when(browser.findElements("//form")).thenReturn(List.of(form));
        when(browser.extractAttributes(form)).thenReturn(Map.of("id", "contact-form"));
        when(client.executeScript(any(), any(), any())).thenReturn("Contact us");
        when(elementService.saveFormElement(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertNotNull(browserService.extractAllForms(1L, domain, browser));

        verify(browser, never()).getDriver();
    }
}
