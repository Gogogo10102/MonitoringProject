package com.monitoring.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Statistics;
import com.monitoring.config.DockerProperties;
import com.monitoring.model.ContainerStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
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
        log.info("=================================================");
        log.info("Initializing Container Status Service");
        log.info("Target Containers: {}", dockerProperties.getTargetContainers());
        log.info("=================================================");

        try {
            dockerClient.pingCmd().exec();
            log.info("✅ Docker connection successful");

            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec();

            log.info("Found {} total containers", containers.size());

            for (Container container : containers) {
                String name = extractContainerName(container.getNames()[0]);
                log.debug("  - Found container: {}", name);

                if (dockerProperties.getTargetContainers().contains(name)) {
                    updateContainerInfo(name);
                    log.info("✅ Initialized monitoring for: {}", name);
                }
            }

            if (containerStatusMap.isEmpty()) {
                log.warn("⚠️  No target containers found!");
                log.warn("    Expected: {}", dockerProperties.getTargetContainers());
                log.warn("    Available containers:");
                for (Container container : containers) {
                    log.warn("      - {}", extractContainerName(container.getNames()[0]));
                }
            }

        } catch (Exception e) {
            log.warn("Docker not available (local development mode): {}", e.getMessage());
        }
    }

    /**
     * 주기적으로 실행 중인 컨테이너의 상태를 업데이트 (5초마다)
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void updateAllContainerStats() {
        containerStatusMap.forEach((name, status) -> {
            // starting 상태가 5초 이상 지속되면 running으로 전환
            if ("starting".equals(status.getPhase())) {
                long elapsed = System.currentTimeMillis() - status.getLastUpdate();
                if (elapsed > 3000) { // 3초 후 running으로
                    status.setPhase("running");
                    status.setProgress(100);
                    status.setStatus("running");
                    status.setLastUpdate(System.currentTimeMillis());

                    webSocketService.broadcast("container_status_update", status);
                    log.info("✅ Container {} is now running", name);
                }
            }

            // 실행 중인 컨테이너만 stats 업데이트
            if ("running".equals(status.getPhase())) {
                updateContainerInfo(name);
                // ✅ 업데이트 후 WebSocket으로 전송!
                webSocketService.broadcast("container_status_update", status);
            }
        });
    }

    public void updateStatus(String containerName, String eventType) {
        if (eventType == null) {
            log.warn("⚠️  Received null eventType for container: {}", containerName);
            return;
        }

        ContainerStatus status = containerStatusMap.getOrDefault(
                containerName,
                ContainerStatus.builder()
                        .containerName(containerName)
                        .build()
        );

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
                // start 후 즉시 컨테이너 정보 조회
                updateContainerInfo(containerName);
                break;
            case "health_status: healthy":
            case "exec_start":
            case "exec_create":
                // 이런 이벤트들은 컨테이너가 이미 실행 중이라는 신호
                if (!"running".equals(status.getPhase())) {
                    status.setPhase("running");
                    status.setProgress(100);
                    status.setStatus("running");
                    log.info("✅ Container {} confirmed running", containerName);
                }
                break;
            case "stop":
            case "pause":
                status.setPhase("stopping");
                status.setProgress(50);
                status.setStatus("stopping");
                break;
            case "die":
            case "kill":
                status.setPhase("stopped");
                status.setProgress(0);
                status.setStatus("stopped");
                status.setCpu("N/A");
                status.setMemory("N/A");
                status.setUptime("N/A");
                break;
            case "destroy":
            case "remove":
                status.setPhase("removed");
                status.setProgress(0);
                status.setStatus("removed");
                status.setCpu("N/A");
                status.setMemory("N/A");
                status.setUptime("N/A");
                break;
            default:
                status.setStatus(eventType);
        }

        status.setLastUpdate(System.currentTimeMillis());
        containerStatusMap.put(containerName, status);

        webSocketService.broadcast("container_status_update", status);

        log.debug("📊 Updated status for {}: {} - {}", containerName, eventType, status.getPhase());
    }

    private void updateContainerInfo(String containerName) {
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withNameFilter(List.of(containerName))
                    .exec();

            if (containers.isEmpty()) {
                log.warn("Container not found: {}", containerName);
                return;
            }

            Container container = containers.get(0);
            String containerId = container.getId();

            InspectContainerResponse info = dockerClient
                    .inspectContainerCmd(containerId)
                    .exec();

            ContainerStatus status = containerStatusMap.get(containerName);
            if (status == null) {
                return;
            }

            // 실행 중인 컨테이너만 stats 조회
            if (info.getState().getRunning()) {
                // Uptime 계산
                if (info.getState().getStartedAt() != null) {
                    status.setUptime(calculateUptime(info.getState().getStartedAt()));
                }

                // Stats 조회 (비동기)
                try {
                    updateContainerStats(containerName, containerId, status);
                } catch (Exception e) {
                    log.debug("Could not get stats for {}: {}", containerName, e.getMessage());
                    status.setCpu("-");
                    status.setMemory("-");
                }
            } else {
                // 중지된 컨테이너
                status.setCpu("N/A");
                status.setMemory("N/A");
                status.setUptime("N/A");
            }

        } catch (Exception e) {
            log.error("Failed to update container info: {}", containerName, e);
        }
    }

    private void updateContainerStats(String containerName, String containerId, ContainerStatus status) {
        try {
            // Stats를 한 번만 가져오기 (stream=false)
            dockerClient.statsCmd(containerId)
                    .withNoStream(true)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Statistics>() {
                        @Override
                        public void onNext(Statistics stats) {
                            if (stats != null && stats.getCpuStats() != null && stats.getMemoryStats() != null) {
                                // CPU 사용률 계산
                                String cpuUsage = calculateCpuUsage(stats);

                                // Memory 사용량 계산
                                String memoryUsage = calculateMemoryUsage(stats);

                                status.setCpu(cpuUsage);
                                status.setMemory(memoryUsage);

                                log.debug("📊 Stats for {}: CPU={}, Memory={}",
                                        containerName, cpuUsage, memoryUsage);
                            }
                        }
                    })
                    .awaitCompletion();

        } catch (Exception e) {
            log.debug("Stats not available for {}", containerName);
            status.setCpu("-");
            status.setMemory("-");
        }
    }

    private String calculateCpuUsage(Statistics stats) {
        try {
            Long cpuDelta = stats.getCpuStats().getCpuUsage().getTotalUsage() -
                    stats.getPreCpuStats().getCpuUsage().getTotalUsage();
            Long systemDelta = stats.getCpuStats().getSystemCpuUsage() -
                    stats.getPreCpuStats().getSystemCpuUsage();

            if (systemDelta > 0 && cpuDelta >= 0) {
                Long onlineCpus = stats.getCpuStats().getOnlineCpus();
                int cpuCount = (onlineCpus != null && onlineCpus > 0) ?
                        onlineCpus.intValue() :
                        (stats.getCpuStats().getCpuUsage().getPercpuUsage() != null ?
                                stats.getCpuStats().getCpuUsage().getPercpuUsage().size() : 1);

                double cpuPercent = (cpuDelta.doubleValue() / systemDelta.doubleValue()) * cpuCount * 100.0;
                return String.format("%.1f%%", cpuPercent);
            }
        } catch (Exception e) {
            log.debug("CPU calculation failed: {}", e.getMessage());
        }
        return "-";
    }

    private String calculateMemoryUsage(Statistics stats) {
        try {
            Long usage = stats.getMemoryStats().getUsage();
            Long limit = stats.getMemoryStats().getLimit();

            if (usage != null && limit != null && limit > 0) {
                double usageMB = usage / 1024.0 / 1024.0;
                double limitMB = limit / 1024.0 / 1024.0;
                double percent = (usage.doubleValue() / limit.doubleValue()) * 100.0;

                return String.format("%.0f/%.0fMB (%.0f%%)", usageMB, limitMB, percent);
            }
        } catch (Exception e) {
            log.debug("Memory calculation failed: {}", e.getMessage());
        }
        return "-";
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
            } else if (minutes > 0) {
                return String.format("%dm", minutes);
            } else {
                return String.format("%ds", duration.toSecondsPart());
            }
        } catch (Exception e) {
            return "N/A";
        }
    }

    private String extractContainerName(String fullName) {
        return fullName.startsWith("/") ? fullName.substring(1) : fullName;
    }

    public Map<String, ContainerStatus> getAllStatus() {
        return new HashMap<>(containerStatusMap);
    }

    public ContainerStatus getStatus(String containerName) {
        return containerStatusMap.get(containerName);
    }
}