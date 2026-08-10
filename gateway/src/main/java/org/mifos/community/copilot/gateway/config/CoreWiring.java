/**
 * Copyright since 2026 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.mifos.community.copilot.gateway.config;

import org.mifos.community.copilot.core.agent.AgentLoop;
import org.mifos.community.copilot.core.approval.ApprovalStore;
import org.mifos.community.copilot.core.convo.ConversationStore;
import org.mifos.community.copilot.core.llm.LlmClient;
import org.mifos.community.copilot.core.llm.OpenAiCompatibleLlmClient;
import org.mifos.community.copilot.core.llm.ScriptedLlmClient;
import org.mifos.community.copilot.core.tools.FineractRestToolExecutor;
import org.mifos.community.copilot.core.tools.ToolExecutor;
import org.mifos.community.copilot.core.tools.ToolManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/** Builds the framework-free core from configuration and exposes it as Spring beans. */
@Configuration
public class CoreWiring {

    private static final Logger log = LoggerFactory.getLogger(CoreWiring.class);

    @Bean
    ToolManifest toolManifest() throws IOException {
        try (InputStream yaml = getClass().getResourceAsStream("/tools.yaml")) {
            ToolManifest manifest = ToolManifest.load(yaml);
            log.info("Loaded {} tools from the default-deny manifest", manifest.size());
            return manifest;
        }
    }

    @Bean
    LlmClient llmClient(GatewayProperties properties) {
        GatewayProperties.Llm llm = properties.llm();
        if ("mock".equalsIgnoreCase(llm.provider()) || llm.provider() == null || llm.provider().isBlank()) {
            log.warn("LLM provider = mock (no API key configured). Tools still execute for real; "
                    + "set COPILOT_LLM_PROVIDER=groq|ollama for a real model.");
            return new ScriptedLlmClient();
        }
        String baseUrl = switch (llm.provider().toLowerCase()) {
            case "groq" -> llm.baseUrl() != null && !llm.baseUrl().isBlank() ? llm.baseUrl()
                    : "https://api.groq.com/openai/v1";
            case "ollama" -> llm.baseUrl() != null && !llm.baseUrl().isBlank() ? llm.baseUrl()
                    : "http://localhost:11434/v1";
            default -> llm.baseUrl();
        };
        log.info("LLM provider = {} ({}), model = {}, data-residency = {}", llm.provider(), baseUrl, llm.model(),
                llm.dataResidency());
        return new OpenAiCompatibleLlmClient(baseUrl, llm.apiKey(), llm.model());
    }

    @Bean
    ToolExecutor toolExecutor(GatewayProperties properties) {
        log.info("Tool executor = direct Fineract REST at {} (officer credential passthrough)",
                properties.fineract().baseUrl());
        return new FineractRestToolExecutor(properties.fineract().baseUrl());
    }

    @Bean
    ApprovalStore approvalStore(GatewayProperties properties) {
        return new ApprovalStore(Duration.ofSeconds(properties.approval().ttlSeconds()));
    }

    @Bean
    ConversationStore conversationStore() {
        return new ConversationStore();
    }

    @Bean
    AgentLoop agentLoop(LlmClient llm, ToolManifest manifest, ToolExecutor executor, ApprovalStore approvals,
            ConversationStore conversations) {
        return new AgentLoop(llm, manifest, executor, approvals, conversations);
    }

    /** Same-philosophy-as-Fineract CORS: explicit origin allow-list, credentialed headers allowed. */
    @Bean
    CorsWebFilter corsWebFilter(GatewayProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.cors().allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Fineract-Platform-TenantId", "X-Correlation-Id",
                "Content-Type", "Accept"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/copilot/**", config);
        return new CorsWebFilter(source);
    }
}
