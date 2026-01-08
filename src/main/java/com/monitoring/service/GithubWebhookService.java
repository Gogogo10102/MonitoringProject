package com.monitoring.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class GithubWebhookService {

    private final WebSocketService webSocketService;

    public void handlePushEvent(Map<String, Object> payload) {
        try {
            String branch = extractBranch(payload);
            String commitMessage = extractCommitMessage(payload);
            String pusher = extractPusher(payload);

            log.info("GitHub Push Event - Branch: {}, Pusher: {}, Message: {}",
                    branch, pusher, commitMessage);

            // WebSocket으로 전송
            Map<String, Object> data = Map.of(
                    "type", "github_push",
                    "branch", branch != null ? branch : "unknown",
                    "commitMessage", commitMessage != null ? commitMessage : "No message",
                    "pusher", pusher != null ? pusher : "unknown",
                    "timestamp", System.currentTimeMillis()
            );

            webSocketService.broadcast("github_push", data);

        } catch (Exception e) {
            log.error("Failed to handle push event", e);
        }
    }

    public void handleWorkflowEvent(Map<String, Object> payload) {
        try {
            Map<String, Object> workflowRun = (Map<String, Object>) payload.get("workflow_run");

            if (workflowRun == null) {
                log.warn("workflow_run is null in payload");
                return;
            }

            String status = (String) workflowRun.get("status");
            String conclusion = (String) workflowRun.get("conclusion");
            String workflowName = (String) workflowRun.get("name");

            log.info("GitHub Workflow Event - Name: {}, Status: {}, Conclusion: {}",
                    workflowName, status, conclusion);

            // WebSocket으로 전송
            Map<String, Object> data = Map.of(
                    "type", "github_workflow",
                    "workflowName", workflowName != null ? workflowName : "unknown",
                    "status", status != null ? status : "unknown",
                    "conclusion", conclusion != null ? conclusion : "unknown",
                    "timestamp", System.currentTimeMillis()
            );

            webSocketService.broadcast("github_workflow", data);

        } catch (Exception e) {
            log.error("Failed to handle workflow event", e);
        }
    }

    private String extractBranch(Map<String, Object> payload) {
        try {
            String ref = (String) payload.get("ref");
            if (ref != null && ref.startsWith("refs/heads/")) {
                return ref.substring("refs/heads/".length());
            }
            return ref;
        } catch (Exception e) {
            log.error("Failed to extract branch", e);
            return null;
        }
    }

    private String extractCommitMessage(Map<String, Object> payload) {
        try {
            Map<String, Object> headCommit = (Map<String, Object>) payload.get("head_commit");
            if (headCommit != null) {
                return (String) headCommit.get("message");
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to extract commit message", e);
            return null;
        }
    }

    private String extractPusher(Map<String, Object> payload) {
        try {
            Map<String, Object> pusher = (Map<String, Object>) payload.get("pusher");
            if (pusher != null) {
                return (String) pusher.get("name");
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to extract pusher", e);
            return null;
        }
    }
}