package com.looksee.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.looksee.browsing.client.BrowsingClient;
import com.looksee.browsing.generated.model.ElementAction;
import com.looksee.browsing.generated.model.ElementState;
import com.looksee.models.TestUser;
import com.looksee.models.journeys.LoginStep;
import com.looksee.models.journeys.SimpleStep;
import com.looksee.services.browser.RemoteBrowser;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link StepExecutor} drives a {@link RemoteBrowser} through
 * only the Browser-facade API — no raw {@code getDriver()} reach-throughs,
 * no leaked Actions chain, no fresh ActionFactory. This is the consumer-side
 * contract for phase 4b/4c journeyExecutor cutover.
 */
class StepExecutorRemoteModeTest {

    private static ElementState state(String handle) {
        return new ElementState().elementHandle(handle).found(true).displayed(true).attributes(Map.of());
    }

    @Test
    void simpleStep_routesThroughPerformClick() throws Exception {
        BrowsingClient client = mock(BrowsingClient.class);
        when(client.findElement(eq("s-1"), eq("(//button)[1]"))).thenReturn(state("h-btn"));
        RemoteBrowser remote = new RemoteBrowser(client, "s-1", "chrome");

        com.looksee.models.ElementState el = new com.looksee.models.ElementState();
        el.setXpath("//button");
        SimpleStep step = new SimpleStep();
        step.setElementState(el);

        new StepExecutor().execute(remote, step);

        verify(client).findElement("s-1", "(//button)[1]");
        verify(client).scrollToElementCentered("s-1", "h-btn");
        verify(client).performElementAction("s-1", "h-btn", ElementAction.CLICK, null);
        // Crucially: the only getDriver()-bypass methods that fired above.
        // StepExecutor must NEVER call client.getSession outside the getCurrentUrl path.
        verify(client, never()).getSession(any());
    }

    @Test
    void loginStep_usesPerformActionForUsernamePasswordSubmit() throws Exception {
        BrowsingClient client = mock(BrowsingClient.class);
        when(client.findElement(eq("s-1"), eq("(//username)[1]"))).thenReturn(state("h-u"));
        when(client.findElement(eq("s-1"), eq("(//password)[1]"))).thenReturn(state("h-p"));
        when(client.findElement(eq("s-1"), eq("(//submit)[1]"))).thenReturn(state("h-s"));
        RemoteBrowser remote = new RemoteBrowser(client, "s-1", "chrome");

        LoginStep step = new LoginStep();
        com.looksee.models.ElementState u = new com.looksee.models.ElementState(); u.setXpath("//username");
        com.looksee.models.ElementState p = new com.looksee.models.ElementState(); p.setXpath("//password");
        com.looksee.models.ElementState s = new com.looksee.models.ElementState(); s.setXpath("//submit");
        step.setUsernameElement(u);
        step.setPasswordElement(p);
        step.setSubmitElement(s);
        step.setTestUser(new TestUser("alice", "secret"));

        new StepExecutor().execute(remote, step);

        verify(client).findElement("s-1", "(//username)[1]");
        verify(client).findElement("s-1", "(//password)[1]");
        verify(client).findElement("s-1", "(//submit)[1]");
        verify(client).performElementAction("s-1", "h-u", ElementAction.SEND_KEYS, "alice");
        verify(client).performElementAction("s-1", "h-p", ElementAction.SEND_KEYS, "secret");
        verify(client).performElementAction("s-1", "h-s", ElementAction.CLICK, "");
    }
}
