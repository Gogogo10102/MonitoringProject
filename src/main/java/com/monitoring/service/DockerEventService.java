package com.monitoring.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.monitoring.config.DockerProperties;
import com.monitoring.model.DockerEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class DockerEventService {

    private final DockerClient dockerClient;
    private final WebSocketService webSocketService;
    private final ContainerStatusService containerStatusService;
    private final DockerProperties dockerProperties;

    private ResultCallback.Adapter<Event> eventCallback;

    @PostConstruct
    public void startListening() {
        log.info("=================================================");
        log.info("Starting Docker Event Listener");
        log.info("Target Containers: {}", dockerProperties.getTargetContainers());
        log.info("=================================================");

        try {
            dockerClient.pingCmd().exec();

            new Thread(() -> {
                try {
                    log.info("🎧 Starting to listen for Docker events...");

                    eventCallback = new EventCallback();

                    dockerClient.eventsCmd()
                            .withEventTypeFilter(EventType.CONTAINER, EventType.IMAGE)
                            .exec(eventCallback)
                            .awaitCompletion();

                } catch (InterruptedException e) {
                    log.warn("Docker events listening interrupted", e);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("Docker events listening failed", e);
                }
            }, "docker-events-listener").start();

            log.info("✅ Docker event listener started successfully");
        } catch (Exception e) {
            log.warn("Docker not available (local development mode): {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stopListening() {
        log.info("Stopping Docker events listener");
        if (eventCallback != null) {
            try {
                eventCallback.close();
            } catch (Exception e) {
                log.error("Failed to close event callback", e);
            }
        }
    }

    private class EventCallback extends ResultCallback.Adapter<Event> {

        @Override
        public void onNext(Event event) {
            try {
                String containerName = extractContainerName(event);

                if (containerName != null && dockerProperties.getTargetContainers().contains(containerName)) {
                    handleDockerEvent(event, containerName);
                }
            } catch (Exception e) {
                log.error("Error processing Docker event", e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            log.error("❌ Docker events stream error", throwable);
            super.onError(throwable);
        }

        @Override
        public void onComplete() {
            log.warn("⚠️  Docker events stream completed");
            super.onComplete();
        }
    }

    private void handleDockerEvent(Event event, String containerName) {
        // ✅ status가 null이면 action을 사용
        String status = event.getStatus();
        String action = event.getAction();

        // status가 null이면 action 사용
        String eventType = (status != null) ? status : action;

        log.info("🐳 Docker Event: {} - {} (action: {})", containerName, eventType, action);

        // 컨테이너 상태 업데이트
        containerStatusService.updateStatus(containerName, eventType);

        // WebSocket으로 이벤트 브로드캐스트
        DockerEventMessage message = DockerEventMessage.builder()
                .type("docker_event")
                .containerName(containerName)
                .eventType(eventType)
                .timestamp(event.getTime())
                .message(String.format("%s: %s", containerName, eventType))
                .build();

        webSocketService.broadcast("docker_event", message);
    }

    private String extractContainerName(Event event) {
        if (event.getActor() == null || event.getActor().getAttributes() == null) {
            return null;
        }

        String name = event.getActor().getAttributes().get("name");

        if (name == null) {
            name = event.getActor().getId();
        }

        return name;
    }
}