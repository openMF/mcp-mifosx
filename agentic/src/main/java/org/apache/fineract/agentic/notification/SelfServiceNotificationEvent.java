/**
 * Copyright since 2026 Mifos Initiative
 *
 * <p>This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy
 * of the MPL was not distributed with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.apache.fineract.agentic.notification;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.springframework.context.ApplicationEvent;

/**
 * Immutable event carrying all data needed for self-service notification delivery, including the
 * originating Fineract tenant context so that async listeners can restore multi-tenant database
 * routing on their own thread.
 */
@Getter
public class SelfServiceNotificationEvent extends ApplicationEvent {

  public enum Type {
    USER_ACTIVATED("user-activated"),
    LOGIN_SUCCESS("login-success"),
    LOGIN_FAILURE("login-failure"),
    LOGOUT("logout"),
    ENROLLMENT_TOKEN("enrollment-token"),
    TRANSFER_SUCCESS("transfer-success"),
    TRANSFER_OTP("transfer-otp"),
    USER_CREATED("user-created"),
    BENEFICIARY_ADDED("beneficiary-added"),
    BENEFICIARY_UPDATED("beneficiary-updated"),
    BENEFICIARY_DELETED("beneficiary-deleted"),
    LOAN_REQUESTED("loan-requested"),
    LOAN_UPDATED("loan-updated"),
    LOAN_WITHDRAWN("loan-withdrawn"),
    SAVINGS_REQUESTED("savings-requested"),
    SAVINGS_UPDATED("savings-updated"),
    SAVINGS_WITHDRAWN("savings-withdrawn"),
    PASSWORD_RESET_REQUESTED("password-reset-requested"),
    PASSWORD_RENEWED("password-renewed"),
    SINPE_ENROLLMENT_OTP("sinpe-enrollment-otp"),
    SINPE_ENROLLMENT_SUCCESS("sinpe-enrollment-success"),
    SINPE_SUBSCRIPTION_CREATED("sinpe-subscription-created"),
    SINPE_SUBSCRIPTION_UPDATED("sinpe-subscription-updated"),
    SINPE_SUBSCRIPTION_DELETED("sinpe-subscription-deleted"),
    SAVINGS_DEPOSIT("savings-deposit"),
    LOGIN_UNKNOWN_DEVICE("login-unknown-device"),
    SAVINGS_WITHDRAWAL("savings-withdrawal"),
    PAYMENT_LINK_CREATED("payment-link-created");

    private final String templatePrefix;

    Type(String templatePrefix) {
      this.templatePrefix = templatePrefix;
    }

    public String getTemplatePrefix() {
      return templatePrefix;
    }
  }

  private final Type type;
  private final Long userId;
  private final String firstName;
  private final String lastName;
  private final String username;
  private final String email;
  private final String mobileNumber;
  private final boolean emailMode;
  private final String ipAddress;
  private final Locale locale;

  /**
   * The Fineract tenant that was active when the event was created. Used by async listeners to
   * restore multi-tenant database routing. May be {@code null} if no tenant was available at
   * creation time.
   */
  private final FineractPlatformTenant tenant;

  /**
   * The business dates that were active when the event was created. Used by async listeners to
   * restore date context on the worker thread. May be {@code null} if no business dates were
   * available at creation time.
   */
  private final HashMap<BusinessDateType, LocalDate> businessDates;

  private final Map<String, Object> contextData; // NEW

  /**
   * Creates a new self-service notification event.
   *
   * @param source the object on which the event initially occurred (never {@code null})
   * @param type the notification event type (never {@code null})
   * @param userId the user identifier (never {@code null})
   * @param firstName user's first name (may be {@code null})
   * @param lastName user's last name (may be {@code null})
   * @param username user's login username (may be {@code null})
   * @param email user's email address (may be {@code null})
   * @param mobileNumber user's mobile number (may be {@code null})
   * @param emailMode {@code true} for email delivery, {@code false} for SMS
   * @param ipAddress the originating IP address (may be {@code null})
   * @param locale the locale for notification content (may be {@code null})
   */
  public SelfServiceNotificationEvent(
      Object source,
      Type type,
      Long userId,
      String firstName,
      String lastName,
      String username,
      String email,
      String mobileNumber,
      boolean emailMode,
      String ipAddress,
      Locale locale,
      FineractPlatformTenant tenant,
      HashMap<BusinessDateType, LocalDate> businessDates,
      Map<String, Object> contextData) {
    super(source);
    this.type = type;
    this.userId = userId;
    this.firstName = firstName;
    this.lastName = lastName;
    this.username = username;
    this.email = email;
    this.mobileNumber = mobileNumber;
    this.emailMode = emailMode;
    this.ipAddress = ipAddress;
    this.locale = locale;
    this.tenant = tenant;
    this.businessDates = businessDates;
    this.contextData = contextData != null ? contextData : new HashMap<>();
  }

  /**
   * Creates a new self-service notification event with explicit tenant context.
   *
   * @param source the object on which the event initially occurred (never {@code null})
   * @param type the notification event type (never {@code null})
   * @param userId the user identifier (never {@code null})
   * @param firstName user's first name (may be {@code null})
   * @param lastName user's last name (may be {@code null})
   * @param username user's login username (may be {@code null})
   * @param email user's email address (may be {@code null})
   * @param mobileNumber user's mobile number (may be {@code null})
   * @param emailMode {@code true} for email delivery, {@code false} for SMS
   * @param ipAddress the originating IP address (may be {@code null})
   * @param locale the locale for notification content (may be {@code null})
   * @param tenant the Fineract tenant active at event creation time (may be {@code null})
   * @param businessDates the business dates active at event creation time (may be {@code null})
   */
  // 13-argument constructor (Without contextData)
  public SelfServiceNotificationEvent(
      Object source,
      Type type,
      Long userId,
      String firstName,
      String lastName,
      String username,
      String email,
      String mobileNumber,
      boolean emailMode,
      String ipAddress,
      Locale locale,
      FineractPlatformTenant tenant,
      HashMap<BusinessDateType, LocalDate> businessDates) {
    this(
        source,
        type,
        userId,
        firstName,
        lastName,
        username,
        email,
        mobileNumber,
        emailMode,
        ipAddress,
        locale,
        tenant,
        businessDates,
        null);
  }

  // 11-argument constructor (Backward-compatible for tests and legacy code)
  public SelfServiceNotificationEvent(
      Object source,
      Type type,
      Long userId,
      String firstName,
      String lastName,
      String username,
      String email,
      String mobileNumber,
      boolean emailMode,
      String ipAddress,
      Locale locale) {
    this(
        source,
        type,
        userId,
        firstName,
        lastName,
        username,
        email,
        mobileNumber,
        emailMode,
        ipAddress,
        locale,
        null,
        null,
        null);
  }

  /**
   * Factory method that creates an event and automatically captures the current thread's Fineract
   * tenant context and business dates. Use this from request-processing threads where the tenant
   * context is still available.
   *
   * <p>This is the <strong>preferred</strong> way to create events from synchronous call sites
   * (e.g. REST controllers, service methods). For events published from {@code
   * TransactionSynchronization.afterCommit()} callbacks where the tenant may already be cleared,
   * capture the tenant <em>before</em> registering the synchronization and pass it to the full
   * constructor.
   *
   * @see #SelfServiceNotificationEvent(Object, Type, Long, String, String, String, String, String,
   *     boolean, String, Locale, FineractPlatformTenant, HashMap)
   */
  public static SelfServiceNotificationEvent withTenantContext(
      Object source,
      Type type,
      Long userId,
      String firstName,
      String lastName,
      String username,
      String email,
      String mobileNumber,
      boolean emailMode,
      String ipAddress,
      Locale locale) {

    FineractPlatformTenant capturedTenant = null;
    try {
      capturedTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
    }
    final FineractPlatformTenant tenantSnapshot = capturedTenant;

    HashMap<BusinessDateType, LocalDate> businessDatesSnapshot = null;
    try {
      HashMap<BusinessDateType, LocalDate> dates = ThreadLocalContextUtil.getBusinessDates();
      businessDatesSnapshot = dates != null ? new HashMap<>(dates) : null;
    } catch (IllegalArgumentException ignored) {
    }

    return new SelfServiceNotificationEvent(
        source,
        type,
        userId,
        firstName,
        lastName,
        username,
        email,
        mobileNumber,
        emailMode,
        ipAddress,
        locale,
        tenantSnapshot,
        businessDatesSnapshot,
        null);
  }

  // Accepts contextData for dynamic template variables
  public static SelfServiceNotificationEvent withTenantContext(
      Object source,
      Type type,
      Long userId,
      String firstName,
      String lastName,
      String username,
      String email,
      String mobileNumber,
      boolean emailMode,
      String ipAddress,
      Locale locale,
      Map<String, Object> contextData) {

    FineractPlatformTenant capturedTenant = null;
    try {
      capturedTenant = ThreadLocalContextUtil.getTenant();
    } catch (IllegalStateException ignored) {
    }
    final FineractPlatformTenant tenantSnapshot = capturedTenant;

    HashMap<BusinessDateType, LocalDate> businessDatesSnapshot = null;
    try {
      HashMap<BusinessDateType, LocalDate> dates = ThreadLocalContextUtil.getBusinessDates();
      businessDatesSnapshot = dates != null ? new HashMap<>(dates) : null;
    } catch (IllegalArgumentException ignored) {
    }

    return new SelfServiceNotificationEvent(
        source,
        type,
        userId,
        firstName,
        lastName,
        username,
        email,
        mobileNumber,
        emailMode,
        ipAddress,
        locale,
        tenantSnapshot,
        businessDatesSnapshot,
        contextData);
  }

  public Map<String, Object> getContextData() {
    return contextData;
  } // NEW

  /**
   * Returns a safe, non-PII string representation suitable for logging. Sensitive fields (email,
   * username, mobileNumber, ipAddress) are excluded.
   */
  @Override
  public String toString() {
    return "SelfServiceNotificationEvent[type="
        + type
        + ", userId="
        + userId
        + ", emailMode="
        + emailMode
        + ", locale="
        + locale
        + ", tenant="
        + (tenant != null ? tenant.getTenantIdentifier() : "null")
        + "]";
  }
}
