package com.monitoring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.monitoring.model.WebSocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Service
@Slf4j
@RequiredArgsConstructor
public class WebSocketService {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContainerStatusService containerStatusService;

    public void addSession(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket session added: {}, total sessions: {}", session.getId(), sessions.size());
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session);
        log.info("WebSocket session removed: {}, total sessions: {}", session.getId(), sessions.size());
    }

    public void sendInitialStatus(WebSocketSession session) {
        try {
            WebSocketMessage message = WebSocketMessage.of(
                    "initial_status",
                    containerStatusService.getAllStatus()
            );

            String json = objectMapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
            log.debug("Initial status sent to session: {}", session.getId());
        } catch (Exception e) {
            log.error("Failed to send initial status to session: {}", session.getId(), e);
        }
    }

    public void broadcast(String type, Object data) {
        WebSocketMessage message = WebSocketMessage.of(type, data);
        broadcast(message);
    }

    public void broadcast(WebSocketMessage message) {
        String json = toJson(message);

        sessions.forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.error("Failed to send message to session: {}", session.getId(), e);
            }
        });

        log.debug("Broadcasted message to {} sessions: {}", sessions.size(), message.getType());
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Failed to serialize object to JSON", e);
            return "{}";
        }
    }
}