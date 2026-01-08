package com.monitoring.controller;

import com.monitoring.model.ContainerStatus;
import com.monitoring.service.ContainerStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/monitoring")
@Slf4j
@RequiredArgsConstructor
public class MonitoringController {

    private final ContainerStatusService containerStatusService;

    /**
     * 모든 컨테이너 상태 조회
     */
    @GetMapping("/containers")
    public ResponseEntity<Map<String, ContainerStatus>> getAllContainers() {
        log.debug("GET /api/monitoring/containers");
        return ResponseEntity.ok(containerStatusService.getAllStatus());
    }

    /**
     * 특정 컨테이너 상태 조회
     */
    @GetMapping("/containers/{name}")
    public ResponseEntity<ContainerStatus> getContainer(@PathVariable String name) {
        log.debug("GET /api/monitoring/containers/{}", name);

        ContainerStatus status = containerStatusService.getStatus(name);
        if (status != null) {
            return ResponseEntity.ok(status);
        }

        return ResponseEntity.notFound().build();
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = Map.of(
                "status", "UP",
                "timestamp", System.currentTimeMillis(),
                "totalContainers", containerStatusService.getAllStatus().size()
        );

        return ResponseEntity.ok(health);
    }
}