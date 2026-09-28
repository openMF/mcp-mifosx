package org.community.mifos.agentic.controller;

import java.util.Map;
import org.community.mifos.agentic.service.AgentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/hermes")
public class AgentController {

    private final AgentService hermesService;

    public AgentController(AgentService hermesService) {
        this.hermesService = hermesService;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "");
        String reply = hermesService.chat(message);
        return Map.of("reply", reply);
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody Map<String, String> body) {
        return hermesService.chatStream(body.getOrDefault("message", ""));
    }
}