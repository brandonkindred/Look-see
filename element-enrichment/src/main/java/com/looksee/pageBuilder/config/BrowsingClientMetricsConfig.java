package com.looksee.pageBuilder.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Applies the {@code consumer=element-enrichment} common tag to every metric when
 * a Micrometer {@link MeterRegistry} is present. This is the consumer-side
 * half of the metric contract described in
 * {@code browser-service/phase-4-consumer-cutover.md} §Observability prereqs:
 * the LookseeCore {@code BrowsingClient} facade emits
 * {@code browser_service_calls} with {@code operation} + {@code outcome} tags;
 * this config adds the {@code consumer} tag so dashboards can filter by caller
 * without the facade needing to know who's calling.
 *
 * <p>Registered as a {@link MeterRegistryCustomizer} (not {@code @ConditionalOnBean}
 * + {@code @PostConstruct}) so Boot applies the tag when Actuator creates the
 * registry, regardless of component-scan vs auto-config ordering.
 */
@Configuration
public class BrowsingClientMetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> browsingClientConsumerTag() {
        return registry -> registry.config()
            .meterFilter(MeterFilter.commonTags(Tags.of("consumer", "element-enrichment")));
    }
}
