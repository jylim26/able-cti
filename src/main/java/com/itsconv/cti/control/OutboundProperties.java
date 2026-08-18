package com.itsconv.cti.control;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cti.outbound")
public record OutboundProperties(String context, long ringTimeoutMs) {
}
