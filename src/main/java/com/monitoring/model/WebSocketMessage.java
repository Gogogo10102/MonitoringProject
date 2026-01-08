package com.monitoring.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebSocketMessage {

    private String type;             // "container_status", "docker_event", "github_webhook", etc.
    private Object data;
    private Long timestamp;

    public static WebSocketMessage of(String type, Object data) {
        return WebSocketMessage.builder()
                .type(type)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}