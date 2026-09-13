/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package org.apache.fineract.agentic.notification;

// ==========================================

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// --- Configuration for Default Beans ---
// ==========================================

@Configuration
@Slf4j
public class DefaultNotificationSendersConfiguration {

  @Bean
  @ConditionalOnMissingBean(EmailNotificationSender.class)
  public EmailNotificationSender defaultEmailNotificationSender() {
    return new DefaultEmailNotificationSender();
  }

  @Bean
  @ConditionalOnMissingBean(SmsNotificationSender.class)
  public SmsNotificationSender defaultSmsNotificationSender() {
    return new DefaultSmsNotificationSender();
  }

  @Bean
  @ConditionalOnMissingBean(WhatsAppNotificationSender.class)
  public WhatsAppNotificationSender defaultWhatsAppNotificationSender() {
    return new DefaultWhatsAppNotificationSender();
  }

  @Bean
  @ConditionalOnMissingBean(InAppNotificationSender.class)
  public InAppNotificationSender defaultInAppNotificationSender() {
    return new DefaultInAppNotificationSender();
  }
}
