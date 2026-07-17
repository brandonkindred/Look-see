package com.looksee.services.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.generated.model.ElementState;
import com.looksee.browsing.generated.model.PageStatus;
import com.looksee.browsing.generated.model.ScreenshotStrategy;
import com.looksee.browsing.generated.model.ScrollOffset;
import com.looksee.browsing.generated.model.Viewport;
import com.looksee.browsing.generated.model.ViewportState;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Point;

class RemoteBrowserTest {

    private BrowsingClient client;
    private RemoteBrowser remote;

    @BeforeEach
    void setUp() {
        client = mock(BrowsingClient.class);
        remote = new RemoteBrowser(client, "session-1", "chrome");
    }

    @Test
    void navigateTo_forwardsToClient() {
        remote.navigateTo("https://example.com");
        verify(client).navigate("session-1", "https://example.com");
    }

    @Test
    void close_deletesSession() {
        remote.close();
        verify(client).deleteSession("session-1");
    }

    @Test
    void getSource_forwardsToClient() {
        when(client.getSource("session-1")).thenReturn("<html/>");
        assertEquals("<html/>", remote.getSource());
    }

    @Test
    void is503Error_derivesFromStatus() {
        when(client.getStatus("session-1")).thenReturn(new PageStatus().is503(true).currentUrl("x"));
        assertTrue(remote.is503Error());
    }

    @Test
    void getBrowserName_returnsStoredValue() {
        assertEquals("chrome", remote.getBrowserName());
    }

    @Test
    void getSessionId_returnsStoredValue() {
        assertEquals("session-1", remote.getSessionId());
    }

    @Test
    void getViewportScrollOffset_derivesFromViewport() {
        when(client.getViewport("session-1")).thenReturn(new ViewportState()
            .viewport(new Viewport().width(1024).height(768))
            .scrollOffset(new ScrollOffset().x(10).y(20)));
        Point p = remote.getViewportScrollOffset();
        assertEquals(10, p.getX());
        assertEquals(20, p.getY());
    }

    @Test
    void getViewportSize_derivesFromViewport() {
        when(client.getViewport("session-1")).thenReturn(new ViewportState()
            .viewport(new Viewport().width(1024).height(768))
            .scrollOffset(new ScrollOffset().x(0).y(0)));
        assertEquals(1024, remote.getViewportSize().getWidth());
        assertEquals(768, remote.getViewportSize().getHeight());
    }

    @Test
    void getXScrollOffset_derivesFromViewport() {
        when(client.getViewport("session-1")).thenReturn(new ViewportState()
            .viewport(new Viewport().width(1).height(1))
            .scrollOffset(new ScrollOffset().x(42).y(0)));
        assertEquals(42L, remote.getXScrollOffset());
    }

    @Test
    void getYScrollOffset_derivesFromViewport() {
        when(client.getViewport("session-1")).thenReturn(new ViewportState()
            .viewport(new Viewport().width(1).height(1))
            .scrollOffset(new ScrollOffset().x(0).y(99)));
        assertEquals(99L, remote.getYScrollOffset());
    }

    @Test
    void getViewportScreenshot_decodesPngBytes() throws Exception {
        when(client.screenshot(eq("session-1"), eq(ScreenshotStrategy.VIEWPORT)))
            .thenReturn(pngBytes(2, 3));
        BufferedImage img = remote.getViewportScreenshot();
        assertNotNull(img);
        assertEquals(2, img.getWidth());
        assertEquals(3, img.getHeight());
    }

    @Test
    void getFullPageScreenshotShutterbug_requestsShutterbugStrategy() throws Exception {
        when(client.screenshot(any(), eq(ScreenshotStrategy.FULL_PAGE_SHUTTERBUG)))
            .thenReturn(pngBytes(5, 5));
        assertNotNull(remote.getFullPageScreenshotShutterbug());
    }

    @Test
    void waitForPageToLoad_isNoop() {
        assertDoesNotThrow(() -> remote.waitForPageToLoad());
        verifyNoInteractions(client);
    }

    // --- The only remaining intentional throw in RemoteBrowser is getDriver.
    // Every other op is wired in phase 3b — see RemoteBrowserElementOpsTest.

    @Test
    void findElement_indexesSourceXpathInSameLookup() {
        ElementState first = new ElementState()
            .elementHandle("f1").found(true).displayed(true).attributes(Map.of());
        when(client.findElement("session-1", "(//form)[1]")).thenReturn(first);

        RemoteWebElement el = (RemoteWebElement) remote.findElement("//form");

        assertEquals("(//form)[1]", el.getSourceXpath());
        assertEquals("f1", el.getElementHandle());
        verify(client).findElement("session-1", "(//form)[1]");
        verify(client, never()).findElement("session-1", "//form");
        verify(client, never()).findElement("session-1", "(//form)[2]");
    }

    @Test
    void findElement_preservesAlreadyIndexedSourceXpath() {
        ElementState only = new ElementState()
            .elementHandle("f1").found(true).displayed(true).attributes(Map.of());
        when(client.findElement("session-1", "(//form)[2]")).thenReturn(only);

        RemoteWebElement el = (RemoteWebElement) remote.findElement("(//form)[2]");

        assertEquals("(//form)[2]", el.getSourceXpath());
        verify(client).findElement("session-1", "(//form)[2]");
    }

    @Test
    void getDriver_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class, () -> remote.getDriver());
    }

    private static byte[] pngBytes(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
