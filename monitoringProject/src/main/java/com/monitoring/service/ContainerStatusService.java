package com.monitoring.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Statistics;
import com.monitoring.config.DockerProperties;
import com.monitoring.model.ContainerStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class ContainerStatusService {

    private final DockerClient dockerClient;
    private final WebSocketService webSocketService;
    private final DockerProperties dockerProperties;

    @Value("${docker.target-containers}")
    private List<String> targetContainers;

    // 모든 컨테이너 상태를 메모리에 저장
    private final Map<String, ContainerStatus> containerStatusMap = new ConcurrentHashMap<>();

    public ContainerStatusService(
            DockerClient dockerClient,
            @Lazy WebSocketService webSocketService,
            DockerProperties dockerProperties) {
        this.dockerClient = dockerClient;
        this.webSocketService = webSocketService;
        this.dockerProperties = dockerProperties;
    }

    @PostConstruct
    public void initializeContainerStatus() {
        log.info("Initializing container status");

        try {
            // Docker 연결 테스트
            dockerClient.pingCmd().exec();
            log.info("Docker connection successful");

            // 컨테이너 조회
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec();

            for (Container container : containers) {
                String name = extractContainerName(container.getNames()[0]);

                if (dockerProperties.getTargetContainers().contains(name)) {
                    updateContainerInfo(name);
                    log.info("Initialized status for container: {}", name);
                }
            }
        } catch (Exception e) {
            log.warn("Docker not available (local development mode): {}", e.getMessage());
            // 로컬 개발 시 에러 무시
        }
    }

    public void updateStatus(String containerName, String eventType) {
        ContainerStatus status = containerStatusMap.getOrDefault(
                containerName,
                ContainerStatus.builder()
                        .containerName(containerName)
                        .build()
        );

        // 이벤트 타입에 따라 phase와 progress 결정
        switch (eventType) {
            case "create":
                status.setPhase("creating");
                status.setProgress(10);
                status.setStatus("created");
                break;
            case "start":
                status.setPhase("starting");
                status.setProgress(50);
                status.setStatus("starting");
                break;
            case "health_status: healthy":
                status.setPhase("running");
                status.setProgress(100);
                status.setStatus("running");
                break;
            case "stop":
                status.setPhase("stopping");
                status.setProgress(50);
                status.setStatus("stopping");
                break;
            case "die":
                status.setPhase("stopped");
                status.setProgress(0);
                status.setStatus("stopped");
                break;
            case "destroy":
                status.setPhase("removed");
                status.setProgress(0);
                status.setStatus("removed");
                break;
            case "kill":
                status.setPhase("killed");
                status.setProgress(0);
                status.setStatus("killed");
                break;
            default:
                status.setStatus(eventType);
        }

        status.setLastUpdate(System.currentTimeMillis());
        containerStatusMap.put(containerName, status);

        // 상세 정보 업데이트 (CPU, Memory 등)
        if ("start".equals(eventType) || eventType.contains("health_status")) {
            updateContainerInfo(containerName);
        }

        // WebSocket으로 브로드캐스트
        webSocketService.broadcast("container_status_update", status);

        log.debug("Updated status for {}: {} - {}", containerName, eventType, status.getPhase());
    }

    private void updateContainerInfo(String containerName) {
        try {
            // 컨테이너 찾기
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withNameFilter(List.of(containerName))
                    .exec();

            if (containers.isEmpty()) {
                log.warn("Container not found: {}", containerName);
                return;
            }

            Container container = containers.get(0);

            // 상세 정보 조회
            InspectContainerResponse info = dockerClient
                    .inspectContainerCmd(container.getId())
                    .exec();

            ContainerStatus status = containerStatusMap.get(containerName);
            if (status != null) {
                // Uptime 계산
                if (info.getState().getStartedAt() != null) {
                    status.setUptime(calculateUptime(info.getState().getStartedAt()));
                }

                // Stats는 실행 중일 때만 조회 가능
                if (info.getState().getRunning()) {
                    updateContainerStats(containerName, container.getId());
                }
            }

        } catch (Exception e) {
            log.error("Failed to update container info: {}", containerName, e);
        }
    }

    private void updateContainerStats(String containerName, String containerId) {
        try {
            // 비동기로 Stats 조회 (블로킹 방지)
            // 실제로는 별도 스레드에서 주기적으로 조회하는 것이 좋음
            // 여기서는 간단히 동기 방식으로 구현

            ContainerStatus status = containerStatusMap.get(containerName);
            if (status != null) {
                // 임시로 고정값 설정 (실제로는 Stats API 사용)
                status.setCpu("N/A");
                status.setMemory("N/A");
            }

        } catch (Exception e) {
            log.error("Failed to update container stats: {}", containerName, e);
        }
    }

    private String calculateUptime(String startedAt) {
        try {
            Instant start = Instant.parse(startedAt);
            Instant now = Instant.now();
            Duration duration = Duration.between(start, now);

            long hours = duration.toHours();
            long minutes = duration.toMinutesPart();

            if (hours > 0) {
                return String.format("%dh %dm", hours, minutes);
            } else {
                return String.format("%dm", minutes);
            }
        } catch (Exception e) {
            return "N/A";
        }
    }

    private String extractContainerName(String fullName) {
        // Docker는 컨테이너 이름을 "/name" 형식으로 반환
        return fullName.startsWith("/") ? fullName.substring(1) : fullName;
    }

    public Map<String, ContainerStatus> getAllStatus() {
        return new HashMap<>(containerStatusMap);
    }

    public ContainerStatus getStatus(String containerName) {
        return containerStatusMap.get(containerName);
    }
}