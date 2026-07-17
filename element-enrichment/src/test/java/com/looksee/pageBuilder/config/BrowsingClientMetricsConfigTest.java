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
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Verifies that {@link BrowsingClientMetricsConfig} exposes a
 * {@link MeterRegistryCustomizer} that applies {@code consumer=element-enrichment}.
 *
 * <p>Boot applies customizers when constructing Actuator registries; this unit
 * test invokes the customizer directly so CI does not need a full Boot context
 * (which would pull GCP ADC via storage auto-config).
 */
class BrowsingClientMetricsConfigTest {

    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
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
    void customizerBeanIsPresent() {
        assertNotNull(context.getBean(MeterRegistryCustomizer.class),
            "MeterRegistryCustomizer bean should be registered unconditionally");
    }

    @Test
    @SuppressWarnings("unchecked")
    void consumerTagAppliedByCustomizer() {
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        MeterRegistryCustomizer<MeterRegistry> customizer =
            context.getBean(MeterRegistryCustomizer.class);
        customizer.customize(meterRegistry);

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
