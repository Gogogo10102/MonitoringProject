package com.monitoring.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventType;
import com.monitoring.config.DockerProperties;
import com.monitoring.model.DockerEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${docker.target-containers}")
    private List<String> targetContainers;

    private ResultCallback.Adapter<Event> eventCallback;

    @PostConstruct
    public void startListening() {
        log.info("Starting Docker events listener");

        try {
            // Docker 연결 테스트
            dockerClient.pingCmd().exec();

            new Thread(() -> {
                try {
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

            log.info("Docker events listener started successfully");
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

                // 모니터링 대상 컨테이너만 처리
                if (containerName != null && targetContainers.contains(containerName)) {
                    handleDockerEvent(event, containerName);
                }
            } catch (Exception e) {
                log.error("Error processing Docker event", e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            log.error("Docker events stream error", throwable);
            super.onError(throwable);
        }

        @Override
        public void onComplete() {
            log.warn("Docker events stream completed");
            super.onComplete();
        }
    }

    private void handleDockerEvent(Event event, String containerName) {
        String status = event.getStatus(); // create, start, stop, die, etc.
        String action = event.getAction();

        log.info("Docker Event: {} - {} (action: {})", containerName, status, action);

        // 컨테이너 상태 업데이트
        containerStatusService.updateStatus(containerName, status);

        // WebSocket으로 이벤트 브로드캐스트
        DockerEventMessage message = DockerEventMessage.builder()
                .type("docker_event")
                .containerName(containerName)
                .eventType(status)
                .timestamp(event.getTime())
                .message(String.format("%s: %s", containerName, status))
                .build();

        webSocketService.broadcast("docker_event", message);
    }

    private String extractContainerName(Event event) {
        if (event.getActor() == null || event.getActor().getAttributes() == null) {
            return null;
        }

        String name = event.getActor().getAttributes().get("name");

        // name이 없으면 ID로 찾기 시도
        if (name == null) {
            name = event.getActor().getId();
        }

        return name;
    }
}