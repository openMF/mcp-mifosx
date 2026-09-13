package org.apache.fineract.agentic.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class SelfServiceNotificationDispatcher {

  private final EmailNotificationSender emailSender;
  private final SmsNotificationSender smsSender;
  private final WhatsAppNotificationSender whatsappSender;
  private final InAppNotificationSender inAppSender;

  public void sendEmail(String to, String subject, String content) {
    log.info("Dispatching Email to {}: {}", to, subject);
    emailSender.send(to, subject, content);
  }

  public void sendSms(String to, String content) {
    log.info("Dispatching SMS to {}: {}", to, content);
    smsSender.send(to, content);
  }

  public void sendWhatsApp(String to, String content) {
    log.info("Dispatching WhatsApp to {}: {}", to, content);
    whatsappSender.send(to, content);
  }

  public void sendInApp(Long userId, String title, String content) {
    log.info("Dispatching In-App notification to user {}: {}", userId, title);
    inAppSender.send(userId, title, content);
  }
}

// ==========================================
// --- Default Implementations ---
// ==========================================

@Slf4j
class DefaultEmailNotificationSender implements EmailNotificationSender {
  @Override
  public void send(String to, String subject, String content) {
    log.info("Default Email Sender - To: {}, Subject: {}, Content: {}", to, subject, content);
  }
}

@Slf4j
class DefaultSmsNotificationSender implements SmsNotificationSender {
  @Override
  public void send(String to, String content) {
    log.info("Default SMS Sender - To: {}, Content: {}", to, content);
  }
}

@Slf4j
class DefaultWhatsAppNotificationSender implements WhatsAppNotificationSender {
  @Override
  public void send(String to, String content) {
    log.info("Default WhatsApp Sender - To: {}, Content: {}", to, content);
  }
}

@Slf4j
class DefaultInAppNotificationSender implements InAppNotificationSender {
  @Override
  public void send(Long userId, String title, String content) {
    log.info("Default In-App Sender - UserId: {}, Title: {}, Content: {}", userId, title, content);
  }
}
