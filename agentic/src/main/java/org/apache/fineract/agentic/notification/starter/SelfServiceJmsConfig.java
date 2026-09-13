/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.agentic.notification.starter;

import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQPrefetchPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.util.StringUtils;

/**
 * Self-contained JMS configuration for the Self-Service Plugin.
 * Creates its own ActiveMQ ConnectionFactory so a durable consumer
 * always appears on the topic fineract.external.events.
 */
@Slf4j
@Configuration
@EnableJms
@ConditionalOnProperty(
    name = "fineract.events.external.producer.jms.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SelfServiceJmsConfig {

  // -------------------------------------------------------------------------
  // Properties – all of them are already present in your docker-compose
  // -------------------------------------------------------------------------

  @Value("${fineract.events.external.producer.jms.broker-url:tcp://localhost:61616}")
  private String brokerUrl;

  @Value("${fineract.events.external.producer.jms.broker-username:admin}")
  private String brokerUsername;

  @Value("${fineract.events.external.producer.jms.broker-password:admin}")
  private String brokerPassword;

  /** Must be unique per JVM. Matches the value you set in docker-compose. */
  @Value("${fineract.external.events.jms.client-id:selfservice-plugin-external-events}")
  private String clientId;
  
  @Value("${fineract.external.events.jms.concurrency:1}")
  private String concurrency;

  @Value("${fineract.external.events.jms.prefetch:1}")
  private int prefetch;

  @Value("${fineract.external.events.jms.recovery-interval:5000}")
  private long recoveryInterval;

  // -------------------------------------------------------------------------
  // Beans
  // -------------------------------------------------------------------------

  /**
   * Dedicated ConnectionFactory. Using a separate clientId keeps the durable
   * subscription independent of Fineract’s own producer connection.
   */
  @Bean(name = "selfServiceJmsConnectionFactory")
  public ConnectionFactory selfServiceJmsConnectionFactory() {
    log.info("============================================================");
    log.info("SelfServiceJmsConfig – creating dedicated ActiveMQ ConnectionFactory");
    log.info("  brokerUrl     = {}", brokerUrl);
    log.info("  clientId      = {}", clientId);
    log.info("  concurrency   = {}", concurrency);
    log.info("  prefetch      = {}", prefetch);
    log.info("  recovery (ms) = {}", recoveryInterval);
    log.info("============================================================");

    ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory();
    factory.setBrokerURL(brokerUrl);

    if (StringUtils.hasText(brokerUsername)) {
      factory.setUserName(brokerUsername);
      factory.setPassword(brokerPassword);
      log.info("Broker credentials configured (username={})", brokerUsername);
    } else {
      log.info("No broker username/password configured – connecting anonymously");
    }

    if (StringUtils.hasText(clientId)) {
      factory.setClientID(clientId);
      log.info("ClientID set to '{}' (required for durable subscription)", clientId);
    } else {
      log.warn("clientId is empty – durable subscription will NOT work correctly");
    }

    ActiveMQPrefetchPolicy prefetchPolicy = new ActiveMQPrefetchPolicy();
    prefetchPolicy.setTopicPrefetch(prefetch);
    prefetchPolicy.setQueuePrefetch(prefetch);
    factory.setPrefetchPolicy(prefetchPolicy);
    factory.setOptimizeAcknowledge(true);
    factory.setConnectResponseTimeout(10_000);

    log.info("ActiveMQ ConnectionFactory created successfully");
    return factory;
  }

  /**
   * Topic listener container factory with durable subscription.
   * This is the bean that the @JmsListener references via containerFactory="...".
   */
  @Bean(name = "topicJmsListenerContainerFactory")
  public JmsListenerContainerFactory<?> topicJmsListenerContainerFactory(
      ConnectionFactory selfServiceJmsConnectionFactory) {

    log.info("Creating topicJmsListenerContainerFactory (pub/sub + durable)");

    DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
    factory.setConnectionFactory(selfServiceJmsConnectionFactory);

    // Topic mode
    factory.setPubSubDomain(true);
    log.info("  pubSubDomain = true");

    // Durable subscription
    factory.setSubscriptionDurable(true);
    if (StringUtils.hasText(clientId)) {
      factory.setClientId(clientId);
      log.info("  subscriptionDurable = true, clientId = {}", clientId);
    } else {
      log.warn("  subscriptionDurable = true but clientId is missing!");
    }

    factory.setConcurrency(concurrency);
    factory.setSessionTransacted(true);
    factory.setCacheLevelName("CACHE_SESSION");
    factory.setRecoveryInterval(recoveryInterval);
    factory.setAutoStartup(true);

    log.info("  concurrency          = {}", concurrency);
    log.info("  sessionTransacted    = true");
    log.info("  cacheLevel           = CACHE_SESSION");
    log.info("  recoveryInterval     = {} ms", recoveryInterval);
    log.info("  autoStartup          = true");
    log.info("topicJmsListenerContainerFactory is ready – "
        + "a durable consumer should now appear on topic fineract.external.events");

    return factory;
  }
}