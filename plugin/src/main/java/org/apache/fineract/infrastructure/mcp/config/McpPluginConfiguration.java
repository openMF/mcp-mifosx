package org.apache.fineract.infrastructure.mcp.config;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.mcp.service.McpToolCallbackProvider;
import org.apache.fineract.infrastructure.mcp.tools.client.ClientCreateTool;
import org.apache.fineract.infrastructure.mcp.tools.client.ClientDetailsTool;
import org.apache.fineract.infrastructure.mcp.tools.client.ClientSearchTool;
import org.apache.fineract.infrastructure.mcp.tools.loan.LoanApprovalTool;
import org.apache.fineract.infrastructure.mcp.tools.loan.LoanDetailsTool;
import org.apache.fineract.infrastructure.mcp.tools.loan.LoanTransactionTool;
import org.apache.fineract.infrastructure.mcp.tools.report.ReportExecutionTool;
import org.apache.fineract.infrastructure.mcp.tools.savings.SavingsAccountTool;
import org.apache.fineract.infrastructure.mcp.tools.savings.SavingsTransactionTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Main configuration class for the MCP Server Plugin.
 *
 * <p>This class registers all MCP tools and configures the Spring AI MCP server
 * integration within the Apache Fineract application context.
 */
@Configuration
@Slf4j
@ConditionalOnProperty(prefix = "fineract.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpPluginConfiguration {

    /**
     * Registers all MCP tool callbacks with the Spring AI framework.
     *
     * <p>This bean aggregates all individual tool implementations and makes them
     * available for MCP client discovery and invocation.
     */
    @Bean
    public List<ToolCallback> fineractMcpToolCallbacks(
            ClientSearchTool clientSearchTool,
            ClientDetailsTool clientDetailsTool,
            ClientCreateTool clientCreateTool,
            LoanDetailsTool loanDetailsTool,
            LoanApprovalTool loanApprovalTool,
            LoanTransactionTool loanTransactionTool,
            SavingsAccountTool savingsAccountTool,
            SavingsTransactionTool savingsTransactionTool,
            ReportExecutionTool reportExecutionTool,
            McpPluginProperties mcpProperties) {

        log.info("Registering Apache Fineract MCP Tools...");

        McpToolCallbackProvider provider = new McpToolCallbackProvider(mcpProperties);

        List<ToolCallback> callbacks = provider.createToolCallbacks(
                clientSearchTool,
                clientDetailsTool,
                clientCreateTool,
                loanDetailsTool,
                loanApprovalTool,
                loanTransactionTool,
                savingsAccountTool,
                savingsTransactionTool,
                reportExecutionTool
        );

        log.info("Registered {} MCP tool(s) for Apache Fineract.", callbacks.size());
        return callbacks;
    }

    /**
     * BeanFactoryPostProcessor to ensure MCP plugin beans are properly prioritized.
     */
    @Bean
    public static BeanFactoryPostProcessor mcpPluginBeanFactoryPostProcessor() {
        return beanFactory -> {
            if (beanFactory instanceof BeanDefinitionRegistry registry) {
                log.debug("MCP Plugin BeanFactoryPostProcessor initialized.");
            }
        };
    }
}