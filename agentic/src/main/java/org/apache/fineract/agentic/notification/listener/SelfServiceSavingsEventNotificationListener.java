/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.agentic.notification.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.BytesMessage;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.avro.MessageV1;
import org.apache.fineract.avro.savings.v1.SavingsAccountDataV1;
import org.apache.fineract.avro.savings.v1.SavingsAccountTransactionDataV1;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.infrastructure.core.service.tenant.TenantDetailsService;
import org.apache.fineract.portfolio.client.data.ClientData;
import org.apache.fineract.portfolio.client.service.ClientReadPlatformService;
import org.apache.fineract.portfolio.savings.data.SavingsAccountData;
import org.apache.fineract.portfolio.savings.service.SavingsAccountReadPlatformService;
import org.apache.fineract.agentic.notification.SelfServiceNotificationEvent;
import org.apache.fineract.useradministration.domain.AppUser;
import org.apache.fineract.useradministration.domain.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Durable JMS topic listener that consumes Fineract external business events
 * ({@code SavingsDepositBusinessEvent} / {@code SavingsWithdrawalBusinessEvent})
 * and turns them into {@link SelfServiceNotificationEvent} instances.
 *
 * <p>Fully multi-tenant: restores the correct {@link FineractPlatformTenant} from the Avro
 * envelope before any database access and always clears the {@code ThreadLocal} afterwards.
 *
 * <p>JMS listener threads have no HTTP security context. Core read services such as
 * {@link ClientReadPlatformService#retrieveOne} call {@code officeHierarchy()}, which requires
 * an authenticated {@link AppUser}. This listener therefore installs a system/audit user
 * (default username {@code mifos}) into {@link SecurityContextHolder} for the duration of
 * message processing — the same pattern used by self-service enrollment.
 */
@Component
@ConditionalOnProperty(
    name = "fineract.events.external.producer.jms.enabled",
    havingValue = "true",
    matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class SelfServiceSavingsEventNotificationListener {

  private final ApplicationEventPublisher eventPublisher;
  private final ClientReadPlatformService clientReadPlatformService;
  private final SavingsAccountReadPlatformService savingsAccountReadPlatformService;
  private final TenantDetailsService tenantDetailsService;
  private final ObjectMapper objectMapper;
  private final AppUserRepository appUserRepository;
  private final JdbcTemplate jdbcTemplate;

  /**
   * Destination = the topic name published by Fineract core.
   * Official property: {@code fineract.events.external.producer.jms.event-topic-name}
   */
  @Value("${fineract.events.external.producer.jms.event-topic-name:fineract.external.events}")
  private String eventTopicName;

  /**
   * Durable subscription name – matches the value already set in docker-compose.
   */
  @Value("${fineract.external.events.jms.subscription-name:selfservice-savings-notifications}")
  private String subscriptionName;

  /**
   * System/audit username used when processing background JMS events (no HTTP principal).
   * Must exist in {@code m_appuser}. Defaults to {@code mifos} (standard Fineract bootstrap user).
   */
  @Value("${fineract.selfservice.events.system-user:mifos}")
  private String systemUsername;

  @jakarta.annotation.PostConstruct
  public void init() {
    log.info("============================================================");
    log.info("SelfServiceSavingsEventNotificationListener STARTED");
    log.info("  destination     = {}", eventTopicName);
    log.info("  subscription    = {}", subscriptionName);
    log.info("  containerFactory= topicJmsListenerContainerFactory");
    log.info("  systemUser      = {}", systemUsername);
    log.info("============================================================");
  }

  @JmsListener(
      destination =
          "${fineract.events.external.producer.jms.event-topic-name:fineract.external.events}",
      containerFactory = "topicJmsListenerContainerFactory",
      subscription =
          "${fineract.external.events.jms.subscription-name:selfservice-savings-notifications}",
      id = "selfServiceSavingsExternalEventsListener")
  public void onMessage(Message message) {
    FineractPlatformTenant originalTenant = null;
    try {
      originalTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
      // Expected on pure JMS threads
    }

    final Authentication previousAuth = SecurityContextHolder.getContext().getAuthentication();

    try {
      byte[] payloadBytes = extractPayloadBytes(message);
      if (payloadBytes == null || payloadBytes.length == 0) {
        log.warn("Received null or empty payload from JMS message");
        return;
      }

      log.info(
          "JMS message received on topic [{}] – Type: {}, Length: {}",
          eventTopicName,
          message.getClass().getSimpleName(),
          payloadBytes.length);

      // Avro MessageV1 envelope
      ByteBuffer byteBuffer = ByteBuffer.wrap(payloadBytes);
      MessageV1 messageV1 = MessageV1.fromByteBuffer(byteBuffer);

      String eventType = messageV1.getType() != null ? messageV1.getType().toString() : null;
      String tenantIdentifier =
          StringUtils.hasText(messageV1.getTenantId()) ? messageV1.getTenantId() : "default";

      log.info(
          "Parsed Avro MessageV1 – type={}, category={}, tenant={}",
          eventType,
          messageV1.getCategory(),
          tenantIdentifier);

      // Multi-tenant context restoration BEFORE any DB call
      FineractPlatformTenant tenant = resolveTenant(tenantIdentifier);
      if (tenant == null) {
        log.error("Unable to resolve tenant [{}]. Aborting message processing.", tenantIdentifier);
        return;
      }

      ThreadLocalContextUtil.setTenant(tenant);
      // ThreadLocalContextUtil.setBusinessDates expects a concrete HashMap
      HashMap<BusinessDateType, LocalDate> businessDates = new HashMap<>();
      businessDates.put(BusinessDateType.BUSINESS_DATE, LocalDate.now());
      ThreadLocalContextUtil.setBusinessDates(businessDates);

      // JMS threads have no HTTP security context. Core services (e.g. ClientReadPlatformService)
      // call authenticatedUser() / officeHierarchy(). Install system user for this thread.
      establishSystemSecurityContext();

      // Process only the events we care about
      if ("SavingsDepositBusinessEvent".equals(eventType)
          || "SavingsWithdrawalBusinessEvent".equals(eventType)) {

        ByteBuffer dataBuffer = messageV1.getData();
        if (dataBuffer == null) {
          log.warn("Event envelope has no data payload for type {}", eventType);
          return;
        }

        SavingsAccountTransactionDataV1 txnData =
            SavingsAccountTransactionDataV1.fromByteBuffer(dataBuffer);
        processSavingsTransactionEvent(txnData, eventType);
      } else if ("SavingsActivateBusinessEvent".equals(eventType)) {

        ByteBuffer dataBuffer = messageV1.getData();
        if (dataBuffer == null) {
          log.warn("Event envelope has no data payload for type {}", eventType);
          return;
        }

        SavingsAccountDataV1 accountDataV1 = SavingsAccountDataV1.fromByteBuffer(dataBuffer);
        
      } else {
        log.debug("Ignoring external event type: {}", eventType);
      }

    } catch (Exception e) {
      log.error("Failed to parse or process external event message", e);
      // Session is transacted → message will be redelivered
      throw new RuntimeException("JMS processing failed – triggering redelivery", e);
    } finally {
      // Always restore previous security context (or clear) to avoid polluting the JMS pool
      if (previousAuth != null) {
        SecurityContextHolder.getContext().setAuthentication(previousAuth);
      } else {
        SecurityContextHolder.clearContext();
      }
      // Always clean ThreadLocal to avoid pollution of the JMS thread pool
      ThreadLocalContextUtil.reset();
      if (originalTenant != null) {
        ThreadLocalContextUtil.setTenant(originalTenant);
      }
    }
  }

  /**
   * Loads the configured system/audit {@link AppUser} and places it in the current thread's
   * {@link SecurityContextHolder}. Required so that core platform read services can call
   * {@code authenticatedUser()} / {@code officeHierarchy()} without throwing
   * {@code UnAuthenticatedUserException}.
   */
  private void establishSystemSecurityContext() {
    AppUser systemUser = resolveSystemUser(systemUsername);
    Authentication auth =
        new UsernamePasswordAuthenticationToken(systemUser, null, systemUser.getAuthorities());
    SecurityContextHolder.getContext().setAuthentication(auth);
    log.debug("Installed system security context for JMS processing as user '{}'", systemUsername);
  }

  private AppUser resolveSystemUser(String username) {
    try {
      Long userId =
          jdbcTemplate.queryForObject(
              "SELECT id FROM m_appuser WHERE username = ? AND is_deleted = false",
              Long.class,
              username);
      if (userId == null) {
        throw new IllegalStateException(
            "System user '"
                + username
                + "' is configured but no matching m_appuser id was returned.");
      }
      return appUserRepository
          .findById(userId)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "System user '"
                          + username
                          + "' was found in m_appuser lookup but could not be loaded from the"
                          + " repository."));
    } catch (EmptyResultDataAccessException e) {
      throw new IllegalStateException(
          "Configured system user '"
              + username
              + "' does not exist in m_appuser. Set fineract.selfservice.events.system-user to a"
              + " valid username.",
          e);
    }
  }

  private FineractPlatformTenant resolveTenant(String tenantIdentifier) {
    try {
      return tenantDetailsService.loadTenantById(tenantIdentifier);
    } catch (Exception e) {
      log.warn(
          "Could not load tenant by id [{}]. Falling back to 'default'. Cause: {}",
          tenantIdentifier,
          e.getMessage());
      try {
        return tenantDetailsService.loadTenantById("default");
      } catch (Exception ex) {
        log.error("Even the default tenant could not be loaded", ex);
        return null;
      }
    }
  }

  private void processSavingsTransactionEvent(
      SavingsAccountTransactionDataV1 txnData, String eventType) {

    // Avro field is "accountId", NOT "savingsAccountId"
    Long savingsAccountId = txnData.getAccountId();
    BigDecimal amount = txnData.getAmount() != null ? txnData.getAmount() : BigDecimal.ZERO;
    String currencyCode =
        txnData.getCurrency() != null ? txnData.getCurrency().getCode() : "XXX";
    LocalDate transactionDate =
        txnData.getDate() != null
            ? LocalDate.parse(txnData.getDate().toString())
            : LocalDate.now();
    String accountNumber = txnData.getAccountNo();

    Long clientId = null;
    if (savingsAccountId != null) {
      try {
        SavingsAccountData accountData =
            savingsAccountReadPlatformService.retrieveOne(savingsAccountId);
        clientId = accountData.getClientId();
        if (accountNumber == null) {
          accountNumber = accountData.getAccountNo();
        }
      } catch (Exception e) {
        log.error("Unable to load SavingsAccountData for id={}", savingsAccountId, e);
        return;
      }
    }

    if (clientId == null) {
      log.warn(
          "No clientId resolved for savingsAccountId={}. Skipping notification.",
          savingsAccountId);
      return;
    }

    triggerNotification(
        clientId, savingsAccountId, accountNumber, amount, currencyCode, transactionDate, eventType);
  }

  private void triggerNotification(
      Long clientId,
      Long savingsAccountId,
      String accountNumber,
      BigDecimal amount,
      String currencyCode,
      LocalDate transactionDate,
      String eventType) {

    ClientData client;
    try {
      client = clientReadPlatformService.retrieveOne(clientId);
    } catch (Exception e) {
      log.error("Unable to load ClientData for clientId={}", clientId, e);
      return;
    }

    String firstName = client.getFirstname();
    String lastName = client.getLastname();
    String email = client.getEmailAddress();
    String mobileNumber = client.getMobileNo();

    SelfServiceNotificationEvent.Type notificationType =
        "SavingsDepositBusinessEvent".equals(eventType)
            ? SelfServiceNotificationEvent.Type.SAVINGS_DEPOSIT
            : SelfServiceNotificationEvent.Type.SAVINGS_WITHDRAWAL;

    log.info(
        "Triggering {} notification – clientId={}, savingsAccountId={}, amount={} {}",
        notificationType,
        clientId,
        savingsAccountId,
        amount.setScale(2, RoundingMode.HALF_UP),
        currencyCode);

    Map<String, Object> contextData = new HashMap<>();
    contextData.put("savingsAccountId", savingsAccountId);
    contextData.put("accountNumber", accountNumber != null ? accountNumber : "N/A");
    contextData.put("amount", amount.setScale(2, RoundingMode.HALF_UP));
    contextData.put("currency", currencyCode);
    contextData.put("transactionDate", transactionDate.toString());
    contextData.put("transactionType", notificationType.name());

    FineractPlatformTenant tenant = null;
    try {
      tenant = ThreadLocalContextUtil.getTenant();
    } catch (Exception ignored) {
      // ignore
    }

    SelfServiceNotificationEvent event =
        new SelfServiceNotificationEvent(
            this,
            notificationType,
            clientId,
            firstName,
            lastName,
            null,
            email,
            mobileNumber,
            false,
            null,
            null,
            tenant,
            null,
            contextData);

    eventPublisher.publishEvent(event);
  }

  private byte[] extractPayloadBytes(Message message) throws Exception {
    if (message instanceof BytesMessage bytesMessage) {
      byte[] byteData = new byte[(int) bytesMessage.getBodyLength()];
      bytesMessage.readBytes(byteData);
      return byteData;
    } else if (message instanceof TextMessage textMessage) {
      // Fallback path (rare – Fineract publishes Avro binary)
      String text = textMessage.getText();
      log.info("Received TextMessage – attempting JSON fallback");
      processJsonPayload(text.getBytes(StandardCharsets.UTF_8));
      return null;
    } else {
      log.warn("Unsupported JMS message type: {}", message.getClass().getName());
      return null;
    }
  }

  private void processJsonPayload(byte[] payloadBytes) {
    try {
      String jsonStr = new String(payloadBytes, StandardCharsets.UTF_8);
      JsonNode rootNode = objectMapper.readTree(jsonStr);
      String eventType = rootNode.has("type") ? rootNode.get("type").asText() : null;
      if ("SavingsDepositBusinessEvent".equals(eventType)
          || "SavingsWithdrawalBusinessEvent".equals(eventType)) {
        log.info("JSON fallback processed for event type: {}", eventType);
        // Extend here if a pure-JSON path is ever needed
      }
    } catch (Exception e) {
      log.error("Failed to process JSON payload", e);
    }
  }

  /**
   * Maps Fineract document type labels (m_code_value.code_value) to PUC holderIdType enums.
   *
   * <p>Known Fineract values:
   * <ul>
   *   <li>Persona Física Nacional → FISICA
   *   <li>Persona Jurídica Nacional → JURIDICA
   *   <li>DIMEX → DIMEX
   *   <li>Pasaporte → PASAPORTE
   * </ul>
   * Allowed by PUC: FISICA, JURIDICA, PASAPORTE, DIMEX, DIDI.
   */
  private String mapToPucHolderIdType(String codeValueOrDescription) {
    if (codeValueOrDescription == null || codeValueOrDescription.isBlank()) {
      return "FISICA";
    }

    String normalized =
        codeValueOrDescription
            .trim()
            .toUpperCase()
            .replace("Á", "A")
            .replace("É", "E")
            .replace("Í", "I")
            .replace("Ó", "O")
            .replace("Ú", "U");

    // Already a valid PUC enum
    if (normalized.equals("FISICA")
        || normalized.equals("JURIDICA")
        || normalized.equals("PASAPORTE")
        || normalized.equals("DIMEX")
        || normalized.equals("DIDI")) {
      return normalized;
    }

    // Exact Fineract labels from m_code_value
    if (normalized.equals("PERSONA FISICA NACIONAL") || normalized.equals("PERSONA FISICA")) {
      return "FISICA";
    }
    if (normalized.equals("PERSONA JURIDICA NACIONAL") || normalized.equals("PERSONA JURIDICA")) {
      return "JURIDICA";
    }
    if (normalized.equals("PASAPORTE") || normalized.equals("PASAPORT")) {
      return "PASAPORTE";
    }
    if (normalized.equals("DIMEX")) {
      return "DIMEX";
    }
    if (normalized.equals("DIDI")) {
      return "DIDI";
    }

    // Fuzzy fallback
    if (normalized.contains("JURID")) {
      return "JURIDICA";
    }
    if (normalized.contains("PASAPORT")) {
      return "PASAPORTE";
    }
    if (normalized.contains("DIMEX")) {
      return "DIMEX";
    }
    if (normalized.contains("DIDI") || normalized.contains("DIPLOMAT")) {
      return "DIDI";
    }
    if (normalized.contains("FISIC")
        || normalized.contains("CEDULA")
        || normalized.contains("NACIONAL")
        || normalized.contains("PERSONA")) {
      return "FISICA";
    }

    log.warn(
        "Unrecognized document type '{}'. Defaulting holderIdType to FISICA for PUC.",
        codeValueOrDescription);
    return "FISICA";
  }

  /**
   * Extrae de forma segura el valor en String de un objeto ExternalId o String.
   */
  private String extractExternalIdValue(Object externalIdObj) {
    if (externalIdObj == null) {
      return "";
    }
    if (externalIdObj instanceof ExternalId extId) {
      return extId.isEmpty() ? "" : extId.getValue();
    }
    if (externalIdObj instanceof String str) {
      return str;
    }
    return externalIdObj.toString();
  }
}