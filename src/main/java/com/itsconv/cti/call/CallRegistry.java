package com.itsconv.cti.call;

import com.itsconv.cti.call.domain.Call;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class CallRegistry {

    private final ConcurrentHashMap<String, Call> calls = new ConcurrentHashMap<>();

    public Optional<Call> find(String linkedid) {
        return linkedid == null ? Optional.empty() : Optional.ofNullable(calls.get(linkedid));
    }

    public void put(Call call) {
        calls.put(call.getLinkedid(), call);
    }

    public void remove(String linkedid) {
        calls.remove(linkedid);
    }

    public int size() {
        return calls.size();
    }
}
