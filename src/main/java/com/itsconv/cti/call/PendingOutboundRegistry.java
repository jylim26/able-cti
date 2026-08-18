package com.itsconv.cti.call;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class PendingOutboundRegistry {

    private static final Duration EXPIRY = Duration.ofSeconds(60);

    private final ConcurrentHashMap<String, PendingOutbound> pendings = new ConcurrentHashMap<>();

    public void register(String channelId, String loginId, String agentInterface, String agentExtension, String customerNumber) {
        cleanup();
        pendings.put(channelId, new PendingOutbound(loginId, agentInterface, agentExtension, customerNumber, Instant.now()));
    }

    public Optional<PendingOutbound> consume(String channelId) {
        return channelId == null ? Optional.empty() : Optional.ofNullable(pendings.remove(channelId));
    }

    public void remove(String channelId) {
        pendings.remove(channelId);
    }

    private void cleanup() {
        Instant limit = Instant.now().minus(EXPIRY);
        pendings.values().removeIf(pending -> pending.registeredAt().isBefore(limit));
    }

    public record PendingOutbound(String loginId, String agentInterface, String agentExtension, String customerNumber, Instant registeredAt) {
    }
}
