package com.looksee.services;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.looksee.browser.Browser;
import com.looksee.models.Domain;
import com.looksee.services.browser.RemoteBrowser;
import java.util.Collections;
import org.junit.jupiter.api.Test;

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
}
