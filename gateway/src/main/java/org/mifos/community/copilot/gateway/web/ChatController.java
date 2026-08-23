/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.gateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mifos.community.copilot.core.agent.AgentLoop;
import org.mifos.community.copilot.core.agent.EventSink;
import org.mifos.community.copilot.core.auth.CallContext;
import org.mifos.community.copilot.core.contract.ChatRequest;
import org.mifos.community.copilot.core.contract.DecisionRequest;
import org.mifos.community.copilot.core.contract.ErrorCode;
import org.mifos.community.copilot.core.contract.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Wire contract v1 endpoints (ADR-001 §03). Each POST answers with an SSE stream; closing the
 * connection (stop button, navigation) cancels the running turn. No auto-retry semantics exist
 * server-side either, because an LLM turn is not idempotent.
 */
@RestController
@RequestMapping("/copilot/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentLoop agentLoop;
    private final org.mifos.community.copilot.gateway.config.GatewayProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChatController(AgentLoop agentLoop,
            org.mifos.community.copilot.gateway.config.GatewayProperties properties) {
        this.agentLoop = agentLoop;
        this.properties = properties;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestBody ChatRequest request, ServerHttpRequest http) {
        // Nothing about the officer's identity is taken from the request body. The office a
        // write lands in is worked out from their own credential, further down.
        return run(http, Map.of(), (context, sink) -> {
            String message = request.message() == null ? "" : request.message().trim();
            if (message.isEmpty() || message.length() > 500) {
                sink.emit(StreamEvent.error(ErrorCode.INTERNAL, "Message must be 1-500 characters.", false));
                sink.emit(StreamEvent.done(""));
                return;
            }
            // Deployment-drift guard: the Copilot must NEVER execute against a different
            // Fineract than the one on the officer's screen. Writes to the "wrong bank"
            // would be silent and catastrophic, so refuse loudly instead.
            Object uiBackend = request.context() == null ? null : request.context().get("backendOrigin");
            if (uiBackend != null && !originsMatch(String.valueOf(uiBackend), properties.fineract().baseUrl())) {
                sink.emit(StreamEvent.error(ErrorCode.INTERNAL,
                        "Configuration mismatch: this assistant executes against "
                                + properties.fineract().baseUrl() + ", but your screen is connected to "
                                + uiBackend + ". Ask your administrator to align the Copilot gateway's "
                                + "FINERACT_BASE_URL with the web-app backend.",
                        false));
                sink.emit(StreamEvent.done(""));
                return;
            }
            agentLoop.runTurn(request.conversationId(), message,
                    request.context() == null ? Map.of() : request.context(), context, sink);
        });
    }

    @PostMapping(value = "/actions/{cardId}/decision", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> decision(@PathVariable String cardId, @RequestBody DecisionRequest request,
            ServerHttpRequest http) {
        // No session facts here on purpose: the ones that matter were captured when the card
        // was raised and travel with it, so the office a write lands in cannot be changed
        // between an officer reading the card and pressing Confirm.
        return run(http, Map.of(), (context, sink) ->
                agentLoop.resume(cardId, request.isApprove(), Map.of(), context, sink));
    }

    /** Shared plumbing: extract identity, bridge the core EventSink onto a reactive SSE stream. */
    private Flux<ServerSentEvent<String>> run(ServerHttpRequest http, Map<String, Object> session,
            BiConsumer<CallContext, EventSink> work) {
        CallContext context = extractContext(http, session);
        return Flux.<ServerSentEvent<String>>create((FluxSink<ServerSentEvent<String>> flux) -> {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            flux.onCancel(() -> cancelled.set(true));
            flux.onDispose(() -> cancelled.set(true));

            EventSink sink = new EventSink() {
                @Override
                public void emit(StreamEvent event) {
                    if (!cancelled.get()) {
                        flux.next(encode(event));
                    }
                }

                @Override
                public boolean isCancelled() {
                    return cancelled.get();
                }
            };

            if (!context.hasCredential()) {
                sink.emit(StreamEvent.error(ErrorCode.AUTH_EXPIRED, "Your session has ended. Sign in again to continue.", true));
                sink.emit(StreamEvent.done(""));
                flux.complete();
                return;
            }

            // The loop is blocking (JDK HttpClient streams); run it off the event loop.
            Schedulers.boundedElastic().schedule(() -> {
                try {
                    work.accept(context, sink);
                } catch (Exception e) {
                    log.error("Copilot turn failed [correlation={}]", context.correlationId(), e);
                    sink.emit(StreamEvent.error(ErrorCode.INTERNAL, "Something went wrong on the gateway.", false));
                    sink.emit(StreamEvent.done(""));
                } finally {
                    flux.complete();
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    /** WebFlux serializes ServerSentEvent into proper "event:"/"data:" SSE framing. */
    private ServerSentEvent<String> encode(StreamEvent event) {
        try {
            return ServerSentEvent.<String>builder(mapper.writeValueAsString(event.data()))
                    .event(event.name())
                    .build();
        } catch (JsonProcessingException e) {
            return ServerSentEvent.<String>builder(
                            "{\"code\":\"INTERNAL\",\"message\":\"encoding failure\",\"retryable\":false}")
                    .event("error")
                    .build();
        }
    }

    /** The officer's forwarded identity. Credentials are never logged (correlation id only). */
    private CallContext extractContext(ServerHttpRequest http, Map<String, Object> session) {
        String authorization = http.getHeaders().getFirst("Authorization");
        String tenant = sanitizeToken(http.getHeaders().getFirst("Fineract-Platform-TenantId"), 64);
        // Client-supplied and later logged/forwarded: restrict to a plain token so it can
        // never inject log lines or malformed downstream headers.
        String correlation = sanitizeToken(http.getHeaders().getFirst("X-Correlation-Id"), 64);
        return new CallContext(
                authorization,
                tenant == null ? "default" : tenant,
                correlation == null ? "cop-" + UUID.randomUUID() : correlation,
                session);
    }

    /** Same scheme+host+port comparison; malformed input counts as a mismatch (fail closed). */
    static boolean originsMatch(String uiBackend, String gatewayFineractUrl) {
        try {
            java.net.URI ui = java.net.URI.create(uiBackend.trim());
            java.net.URI gateway = java.net.URI.create(gatewayFineractUrl.trim());
            return ui.getScheme() != null && ui.getScheme().equalsIgnoreCase(gateway.getScheme())
                    && ui.getHost() != null && ui.getHost().equalsIgnoreCase(gateway.getHost())
                    && ui.getPort() == gateway.getPort();
        } catch (Exception e) {
            return false;
        }
    }

    /** Keep [A-Za-z0-9._-] only; null when nothing safe remains. */
    private String sanitizeToken(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }
}
