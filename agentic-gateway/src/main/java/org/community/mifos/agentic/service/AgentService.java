package org.community.mifos.agentic.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class AgentService {

    private final ChatClient chatClient;

    public AgentService(ChatClient.Builder chatClientBuilder) {
        // or inject the bean from HermesConfig
        this.chatClient = chatClientBuilder.build();
    }

    /** Simple non-streaming call */
    public String chat(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }

    /** Streaming response */
    public Flux<String> chatStream(String userMessage) {
        return chatClient.prompt()
                .user(userMessage)
                .stream()
                .content();
    }

    /** Multi-turn with conversation history (Spring AI handles messages) */
    public String chatWithHistory(String userMessage, String conversationId) {
        // You can store history yourself or rely on Hermes session if the API supports it
        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }
}