package com.monitoring.controller;

import com.monitoring.service.GithubWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/webhook")
@Slf4j
@RequiredArgsConstructor
public class WebhookController {

    private final GithubWebhookService githubWebhookService;

    @PostMapping("/github")
    public ResponseEntity<String> handleGithubWebhook(
            @RequestHeader(value = "X-GitHub-Event", required = false) String event,
            @RequestBody Map<String, Object> payload
    ) {
        log.info("GitHub Webhook received: event={}", event);

        if (event == null) {
            log.warn("X-GitHub-Event header is missing");
            return ResponseEntity.badRequest().body("Missing X-GitHub-Event header");
        }

        try {
            switch (event) {
                case "push":
                    githubWebhookService.handlePushEvent(payload);
                    break;
                case "workflow_run":
                    githubWebhookService.handleWorkflowEvent(payload);
                    break;
                case "ping":
                    log.info("GitHub webhook ping received");
                    break;
                default:
                    log.info("Unhandled GitHub event type: {}", event);
            }

            return ResponseEntity.ok("Webhook processed successfully");

        } catch (Exception e) {
            log.error("Failed to process GitHub webhook", e);
            return ResponseEntity.internalServerError().body("Failed to process webhook");
        }
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok("Webhook endpoint is working!");
    }
}