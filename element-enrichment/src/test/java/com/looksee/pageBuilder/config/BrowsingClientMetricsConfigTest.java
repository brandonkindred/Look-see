package com.looksee.pageBuilder.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Verifies that {@link BrowsingClientMetricsConfig} applies the
 * {@code consumer=element-enrichment} common tag to meters registered after
 * the filter is installed.
 *
 * <p>The test bootstraps an {@link AnnotationConfigApplicationContext} directly
 * rather than going through {@code @SpringBootTest}: a Boot-context bringup
 * triggers {@code GcpStorageAutoConfiguration} (and friends) demanding real
 * Application Default Credentials in CI. The unit under test only needs a
 * {@link MeterRegistry} to be registered, then the config class to be
 * processed so its {@code @PostConstruct} fires — this is what the manual
 * context setup does, deterministically and without auto-configuration noise.
 *
 * <p>Registration order matters: {@code SimpleMeterRegistry} is registered as
 * a singleton <em>before</em> {@link BrowsingClientMetricsConfig} is added to
 * the context, so the {@link org.springframework.boot.autoconfigure.condition.ConditionalOnBean}
 * check on the config sees an existing {@code MeterRegistry} bean and the
 * {@code @PostConstruct} runs. In a plain Spring (non-Boot) context the
 * evaluation timing of {@code @ConditionalOnBean} is order-sensitive — Boot's
 * auto-configuration ordering normally guarantees user beans are registered
 * first, but a hand-rolled test has to enforce that explicitly.
 *
 * <p>{@link #consumerTagAppliedByFilter()} deliberately registers a meter
 * <em>without</em> a {@code consumer} tag at the call site: the only way the
 * resulting meter ID can carry {@code consumer=element-enrichment} is if the
 * filter ran. Pre-supplying the tag in the call would defeat the test.
 * {@link #negativeControl_freshRegistryHasNoTag()} guards against the case
 * where some other auto-config inadvertently makes the positive assertion
 * pass for the wrong reason.
 */
class BrowsingClientMetricsConfigTest {

    private AnnotationConfigApplicationContext context;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        meterRegistry = new SimpleMeterRegistry();
        context.getBeanFactory().registerSingleton("meterRegistry", meterRegistry);
        context.register(BrowsingClientMetricsConfig.class);
        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void meterRegistryBeanIsPresent() {
        assertNotNull(context.getBean(MeterRegistry.class),
            "MeterRegistry bean should be wired into the context");
    }

    @Test
    void consumerTagAppliedByFilter() {
        Counter counter = meterRegistry.counter("phase4c.metrics.test");
        assertEquals("element-enrichment", counter.getId().getTag("consumer"),
            "MeterFilter.commonTags() should inject consumer=element-enrichment on meters with no consumer tag");
    }

    @Test
    void negativeControl_freshRegistryHasNoTag() {
        MeterRegistry freshRegistry = new SimpleMeterRegistry();
        Counter counter = freshRegistry.counter("phase4c.metrics.test");
        assertNull(counter.getId().getTag("consumer"),
            "An untouched SimpleMeterRegistry must not synthesize a consumer tag");
    }
}
