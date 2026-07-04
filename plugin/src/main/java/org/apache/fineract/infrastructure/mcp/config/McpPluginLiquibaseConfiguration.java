package org.apache.fineract.infrastructure.mcp.config;

import java.util.List;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Liquibase configuration for MCP plugin database migrations.
 *
 * <p>This configuration ensures that any database changes required by the MCP plugin
 * (such as audit tables or tool execution logs) are applied to all tenants.
 */
@Configuration
@Slf4j
@ConditionalOnProperty(prefix = "fineract.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpPluginLiquibaseConfiguration {

    @Autowired
    private TenantDetailsService tenantDetailsService;

    @Autowired
    @Qualifier("routingDataSource")
    private DataSource routingDataSource;

    /**
     * Executes MCP plugin database migrations for all tenants.
     *
     * <p>This bean runs after the core Fineract database setup is complete,
     * ensuring that tenant databases are properly initialized before
     * applying plugin-specific migrations.
     */
    @Bean
    @DependsOn("tenantDatabaseUpgradeService")
    public String runMcpPluginMigrations() {
        log.info("****************************************************************");
        log.info("*       MCP Plugin Database Migrations Starting                *");
        log.info("****************************************************************");

        List<FineractPlatformTenant> tenants = tenantDetailsService.findAllTenants();

        for (FineractPlatformTenant tenant : tenants) {
            log.info("Running MCP Plugin migrations for tenant: {}", tenant.getTenantIdentifier());

            try {
                // Force the database connection to route to THIS specific tenant
                ThreadLocalContextUtil.setTenant(tenant);

                // Initialize Liquibase for the tenant
                SpringLiquibase liquibase = new SpringLiquibase();
                liquibase.setDataSource(routingDataSource);
                liquibase.setChangeLog("classpath:/db/changelog/tenant/module/mcp/module-changelog-master.xml");
                liquibase.setShouldRun(true);

                // Execute the migration
                liquibase.afterPropertiesSet();

                log.info("Successfully migrated MCP Plugin for tenant: {}", tenant.getTenantIdentifier());

            } catch (Exception e) {
                log.error("Failed to migrate MCP Plugin for tenant: {}", tenant.getTenantIdentifier(), e);
                throw new RuntimeException("MCP Plugin Migration Failed", e);
            } finally {
                // Always clear the context so we don't leak connections
                ThreadLocalContextUtil.clearTenant();
            }
        }

        log.info("****************************************************************");
        log.info("*       MCP Plugin Database Migrations Completed               *");
        log.info("****************************************************************");

        return "MCP Plugin Migrations Completed";
    }
}