package org.apache.fineract.agentic.notification;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.fineract.template.domain.Template;
import org.apache.fineract.template.domain.TemplateEntity;
import org.apache.fineract.template.domain.TemplateMapper;
import org.apache.fineract.template.domain.TemplateRepository;
import org.apache.fineract.template.domain.TemplateType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SelfServiceTemplateService {

  // Inject the core Fineract repository instead of a custom one
  private final TemplateRepository templateRepository;

  @Transactional(readOnly = true)
  public String mergeTemplate(
      SelfServiceNotificationEvent.Type type, String channel, Map<String, Object> params) {
    String templateName = "SELF_SERVICE_" + type.name() + "_" + channel;

    // Fetch all templates for CLIENT entity (0) and SMS type (0).
    // We use this combination because EMAIL is commented out in Fineract's TemplateType enum.
    // We then filter by our unique template name in Java.
    List<Template> templates =
        templateRepository.findByEntityAndType(TemplateEntity.CLIENT, TemplateType.SMS);

    Optional<Template> templateOpt =
        templates.stream().filter(t -> templateName.equals(t.getName())).findFirst();

    if (templateOpt.isEmpty()) {
      log.warn("Template not found for name: {}. Using default message.", templateName);
      return getDefaultMessage(type, channel);
    }

    Template template = templateOpt.get();

    return mergeTemplateText(template.getText(), template.getMappers(), params);
  }

  private String mergeTemplateText(
      String templateText, Iterable<TemplateMapper> mappers, Map<String, Object> params) {
    if (templateText == null) {
      return "";
    }

    String result = templateText;

    if (mappers != null && mappers.iterator().hasNext()) {
      // Use the correct getter name from Fineract core: getMapperkey()
      for (TemplateMapper mapper : mappers) {
        String key = mapper.getMapperkey();
        Object value = params.get(key);
        result = replacePlaceholder(result, key, value != null ? value.toString() : "");
      }
    } else {
      // Fallback: replace all known params directly if no mappers are defined
      for (Map.Entry<String, Object> entry : params.entrySet()) {
        result =
            replacePlaceholder(
                result,
                entry.getKey(),
                entry.getValue() != null ? entry.getValue().toString() : "");
      }
    }

    return result;
  }

  private String replacePlaceholder(String text, String key, String value) {
    text = text.replace("${" + key + "}", value);
    text = text.replace("{" + key + "}", value);
    return text;
  }

  private String getDefaultMessage(SelfServiceNotificationEvent.Type type, String channel) {
    return "Notification for event: " + type.name() + " via " + channel;
  }
}
