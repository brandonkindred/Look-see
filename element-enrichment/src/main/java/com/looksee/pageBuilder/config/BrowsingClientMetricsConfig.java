package com.looksee.pageBuilder.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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
 * <p>Guarded by {@link ConditionalOnBean}: if element-enrichment is deployed without
 * a {@code MeterRegistry} (no Micrometer registry configured), this config is
 * silently skipped and nothing breaks — same safety contract as the BrowsingClient
 * registration.
 */
@Configuration
@ConditionalOnBean(MeterRegistry.class)
public class BrowsingClientMetricsConfig {

    @Autowired
    private MeterRegistry meterRegistry;

    @PostConstruct
    public void applyConsumerCommonTag() {
        meterRegistry.config().meterFilter(MeterFilter.commonTags(Tags.of("consumer", "element-enrichment")));
    }
}
