package com.itsconv.cti.ami;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "asterisk.ami")
public record AmiProperties(String host, int port, String username, String password) {
}
