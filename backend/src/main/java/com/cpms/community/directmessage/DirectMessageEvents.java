package com.cpms.community.directmessage;

import com.cpms.community.Account;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes "something changed" signals to open Messages pages and home pages over Server-Sent Events.
 * Events carry only ids, never message text; browsers then fetch the data through the normal, access-checked API.
 * Streams close after 10 minutes and the browser reconnects, which re-checks the session.
 */
@Component
public class DirectMessageEvents {
    public static final long STREAM_MS = 10 * 60 * 1000;
    /** type: "message" (new message) or "read" (someone read a conversation). */
    public record Event(String type, Long conversationId, Long messageId) {}

    private record Listener(Long accountId, Account.Role role, String community) {}

    private final Map<SseEmitter, Listener> listeners = new ConcurrentHashMap<>();

    public SseEmitter open(Account account) {
        SseEmitter emitter = new SseEmitter(STREAM_MS);
        Listener listener = new Listener(account.id, account.role, account.community);
        listeners.put(emitter, listener);
        Runnable remove = () -> listeners.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        // Tell the browser it is connected and how long to wait before reconnecting after a drop.
        send(emitter, SseEmitter.event().name("ready").reconnectTime(3000).data("{}", MediaType.APPLICATION_JSON));
        return emitter;
    }

    /** Sends the event to the conversation's resident and to the community's managers once the transaction commits. */
    public void publish(DirectConversation conversation, Long residentId, Event event) {
        String community = conversation.community;
        Runnable deliver = () -> listeners.forEach((emitter, who) -> {
            boolean resident = who.role() == Account.Role.RESIDENT && who.accountId().equals(residentId);
            boolean manager = who.role() == Account.Role.MANAGER && who.community().equals(community);
            if (resident || manager)
                send(emitter, SseEmitter.event().name("dm").data(event, MediaType.APPLICATION_JSON));
        });
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { deliver.run(); }
            });
        else deliver.run();
    }

    /** A comment line every 25 seconds keeps proxies from closing idle streams and drops closed ones. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        listeners.keySet().forEach(emitter -> send(emitter, SseEmitter.event().comment("keep-alive")));
    }

    int listenerCount() { return listeners.size(); }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            listeners.remove(emitter);
            emitter.completeWithError(e);
        }
    }

}
