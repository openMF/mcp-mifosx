/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.core.convo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation state, namespaced by the caller's security fingerprint so no user or
 * tenant can ever read another's turns (the cross-user thread-bleed failure mode is designed out).
 * Message shape is OpenAI-style maps, capped to the last {@link #MAX_MESSAGES} per conversation.
 *
 * <p>Durable multi-device history is a later increment (ADR-001 cut list); the web-app keeps its
 * own localStorage copy meanwhile.
 */
public final class ConversationStore {

    private static final int MAX_MESSAGES = 40;
    private static final int MAX_CONVERSATIONS_PER_USER = 20;

    /** fingerprint -> conversationId -> messages */
    private final Map<String, Map<String, List<Map<String, Object>>>> store = new ConcurrentHashMap<>();

    /** Returns the existing conversation or starts one; never crosses fingerprints. */
    public String resolve(String fingerprint, String conversationId) {
        Map<String, List<Map<String, Object>>> conversations = userStore(fingerprint);
        // The inner LinkedHashMap is NOT thread-safe; the same officer can run concurrent
        // turns (two tabs, chat + decision), so every access locks the per-user map.
        synchronized (conversations) {
            if (conversationId != null && conversations.containsKey(conversationId)) {
                return conversationId;
            }
            String id = "conv-" + UUID.randomUUID();
            conversations.put(id, new ArrayList<>());
            if (conversations.size() > MAX_CONVERSATIONS_PER_USER) {
                String oldest = conversations.keySet().iterator().next();
                conversations.remove(oldest);
            }
            return id;
        }
    }

    public List<Map<String, Object>> messages(String fingerprint, String conversationId) {
        Map<String, List<Map<String, Object>>> conversations = userStore(fingerprint);
        synchronized (conversations) {
            return conversations.getOrDefault(conversationId, new ArrayList<>());
        }
    }

    public void append(String fingerprint, String conversationId, Map<String, Object> message) {
        Map<String, List<Map<String, Object>>> conversations = userStore(fingerprint);
        List<Map<String, Object>> messages;
        synchronized (conversations) {
            messages = conversations.computeIfAbsent(conversationId, (id) -> new ArrayList<>());
        }
        synchronized (messages) {
            messages.add(message);
            while (messages.size() > MAX_MESSAGES) {
                messages.remove(0);
            }
        }
    }

    private Map<String, List<Map<String, Object>>> userStore(String fingerprint) {
        return store.computeIfAbsent(fingerprint, (key) -> new LinkedHashMap<>());
    }
}
