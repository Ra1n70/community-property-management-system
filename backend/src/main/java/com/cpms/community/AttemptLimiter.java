package com.cpms.community;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/** Local, single-process rate limit. Replace with a shared store before multi-instance deployment. */
@Component
public class AttemptLimiter {
    private record Window(Instant expires, int count) {}
    private final Map<String,Window> windows = new HashMap<>();
    public synchronized boolean allow(String key, int limit) {
        Instant now=Instant.now();
        windows.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
        Window previous=windows.get(key);
        if(previous==null) {
            if(windows.size()>=10000)return false;
            windows.put(key,new Window(now.plusSeconds(900),1));return true;
        }
        if(previous.count()>=limit)return false;
        windows.put(key,new Window(previous.expires(),previous.count()+1));return true;
    }
}
