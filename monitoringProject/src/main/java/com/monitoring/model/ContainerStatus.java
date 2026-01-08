package com.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContainerStatus {

    private String containerName;
    private String status;          // running, stopped, starting, etc.
    private String phase;            // creating, starting, running, stopping, stopped
    private Integer progress;        // 0-100
    private Long lastUpdate;         // timestamp

    // 리소스 정보
    private String cpu;              // CPU 사용률
    private String memory;           // 메모리 사용량
    private String uptime;           // 가동 시간

    // 의존성 정보
    private String waitingFor;       // 대기 중인 컨테이너 이름
}