package com.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DockerEventMessage {

    private String type;             // "docker_event"
    private String containerName;
    private String eventType;        // create, start, stop, die, etc.
    private Long timestamp;
    private String message;
}