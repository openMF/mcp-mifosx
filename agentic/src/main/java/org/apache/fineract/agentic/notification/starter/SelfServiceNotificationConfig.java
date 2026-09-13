/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.agentic.notification.starter;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.agentic.notification.NotificationContext;
import org.apache.fineract.agentic.notification.NotificationCooldownCache;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.FileTemplateResolver;

/**
 * Spring configuration for self-service notification infrastructure.
 *
 * @author Mifos
 * @since 1.15.0
 */
@Slf4j
@Configuration
@EnableAsync
@EnableConfigurationProperties(SelfServiceNotificationConfig.NotificationExecutorProperties.class)
public class SelfServiceNotificationConfig {

  /**
   * Builds the async executor used for self-service notification handling.
   *
   * @param meterRegistry optional metrics registry for dropped notification counters
   * @return configured notification executor
   */
  @Bean
  public Executor notificationExecutor(
      Optional<MeterRegistry> meterRegistry, NotificationExecutorProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.getCorePoolSize());
    executor.setMaxPoolSize(properties.getMaxPoolSize());
    executor.setQueueCapacity(properties.getQueueCapacity());
    executor.setThreadNamePrefix(properties.getThreadNamePrefix());
    executor.setTaskDecorator(
        runnable -> {
          FineractPlatformTenant capturedTenant = null;
          try {
            capturedTenant = ThreadLocalContextUtil.getTenant();
          } catch (IllegalStateException ignored) {
          }
          final FineractPlatformTenant tenant = capturedTenant;
          HashMap<BusinessDateType, LocalDate> businessDates = currentBusinessDates();
          return () -> {
            try {
              if (tenant != null) {
                ThreadLocalContextUtil.setTenant(tenant);
              }
              if (businessDates != null) {
                ThreadLocalContextUtil.setBusinessDates(new HashMap<>(businessDates));
              }
              runnable.run();
            } finally {
              ThreadLocalContextUtil.reset();
            }
          };
        });
    executor.setRejectedExecutionHandler(
        (Runnable r, ThreadPoolExecutor e) -> {
          String eventType = Optional.ofNullable(NotificationContext.get()).orElse("unknown");
          meterRegistry.ifPresent(
              mr ->
                  mr.counter("selfservice.notifications.dropped", "event", eventType).increment());
          log.warn(
              "Notification dropped due to thread pool saturation for event type: {}", eventType);
        });
    executor.initialize();
    return executor;
  }

  private HashMap<BusinessDateType, LocalDate> currentBusinessDates() {
    try {
      HashMap<BusinessDateType, LocalDate> businessDates =
          ThreadLocalContextUtil.getBusinessDates();
      return businessDates == null ? null : new HashMap<>(businessDates);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * Builds the Thymeleaf template engine used for self-service notification rendering.
   *
   * @param env environment used to resolve template path and cache settings
   * @return configured notification template engine
   */
  @Bean
  @Primary
  public TemplateEngine notificationTemplateEngine(Environment env) {
    SpringTemplateEngine engine = new SpringTemplateEngine();
    boolean cacheTemplates =
        env.getProperty("fineract.selfservice.notification.cache-templates", Boolean.class, true);

    String externalPath = env.getProperty("fineract.selfservice.notification.template-path", "");
    if (StringUtils.isNotBlank(externalPath)) {
      String normalizedPath = externalPath.endsWith("/") ? externalPath : externalPath + "/";

      FileTemplateResolver htmlFileResolver = new FileTemplateResolver();
      htmlFileResolver.setOrder(1);
      htmlFileResolver.setPrefix(normalizedPath);
      htmlFileResolver.setSuffix(".html");
      htmlFileResolver.setTemplateMode(TemplateMode.HTML);
      htmlFileResolver.setCheckExistence(true);
      htmlFileResolver.setCacheable(cacheTemplates);
      engine.addTemplateResolver(htmlFileResolver);

      FileTemplateResolver textFileResolver = new FileTemplateResolver();
      textFileResolver.setOrder(2);
      textFileResolver.setPrefix(normalizedPath);
      textFileResolver.setSuffix(".txt");
      textFileResolver.setTemplateMode(TemplateMode.TEXT);
      textFileResolver.setCheckExistence(true);
      textFileResolver.setCacheable(cacheTemplates);
      engine.addTemplateResolver(textFileResolver);
    }

    ClassLoaderTemplateResolver htmlClasspathResolver = new ClassLoaderTemplateResolver();
    htmlClasspathResolver.setOrder(3);
    htmlClasspathResolver.setPrefix("notification/templates/");
    htmlClasspathResolver.setSuffix(".html");
    htmlClasspathResolver.setTemplateMode(TemplateMode.HTML);
    htmlClasspathResolver.setCheckExistence(true);
    htmlClasspathResolver.setCacheable(cacheTemplates);
    engine.addTemplateResolver(htmlClasspathResolver);

    ClassLoaderTemplateResolver textClasspathResolver = new ClassLoaderTemplateResolver();
    textClasspathResolver.setOrder(4);
    textClasspathResolver.setPrefix("notification/templates/");
    textClasspathResolver.setSuffix(".txt");
    textClasspathResolver.setTemplateMode(TemplateMode.TEXT);
    textClasspathResolver.setCheckExistence(true);
    textClasspathResolver.setCacheable(cacheTemplates);
    engine.addTemplateResolver(textClasspathResolver);

    engine.setTemplateEngineMessageSource(notificationMessageSource());
    return engine;
  }

  /**
   * Provides the message source for localized notification template messages.
   *
   * @return configured notification message source
   */
  @Bean
  public ResourceBundleMessageSource notificationMessageSource() {
    ResourceBundleMessageSource src = new ResourceBundleMessageSource();
    src.setBasename("notification/messages/NotificationMessages");
    src.setDefaultEncoding("UTF-8");
    return src;
  }

  /**
   * Provides the cooldown cache for rate-limiting duplicate notifications.
   *
   * @param env environment used to resolve cooldown configuration
   * @return configured notification cooldown cache
   */
  @Bean
  public NotificationCooldownCache notificationCooldownCache(Environment env) {
    int cooldownSeconds =
        env.getProperty("fineract.selfservice.notification.cooldown-seconds", Integer.class, 300);
    int maxCacheSize =
        env.getProperty(
            "fineract.selfservice.notification.cooldown-max-size", Integer.class, 10_000);
    return new NotificationCooldownCache(Duration.ofSeconds(cooldownSeconds), maxCacheSize);
  }

  /** Configuration properties for the notification thread pool executor. */
  @ConfigurationProperties(prefix = "fineract.selfservice.notification.executor")
  @org.springframework.validation.annotation.Validated
  @lombok.Getter
  @lombok.Setter
  public static class NotificationExecutorProperties {

    @jakarta.validation.constraints.Min(1) private int corePoolSize = 2;

    @jakarta.validation.constraints.Min(1) private int maxPoolSize = 4;

    @jakarta.validation.constraints.Min(0) private int queueCapacity = 100;

    private String threadNamePrefix = "notif-";
  }
}
