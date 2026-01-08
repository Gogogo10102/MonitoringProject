package com.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PipelineStatus {

    private String currentPhase;     // "github_push", "deploying", "completed"
    private Long startTime;
    private Long endTime;
    private String status;           // "running", "success", "failed"

    // 각 컨테이너 상태
    private Map<String, ContainerStatus> containers;

    // GitHub 정보
    private String branch;
    private String commitMessage;
    private String githubActionsStatus;
}