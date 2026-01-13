package com.monitoring.controller;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Frame;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/logs")
@Slf4j
@RequiredArgsConstructor
public class LogStreamingController {

    private final DockerClient dockerClient;

    /**
     * 컨테이너 최근 로그 조회 (텍스트)
     */
    @GetMapping(value = "/{containerName}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String getRecentLogs(@PathVariable String containerName,
                                @RequestParam(defaultValue = "100") int lines) {
        log.info("Fetching recent logs for container: {}, lines: {}", containerName, lines);

        try {
            StringBuilder logs = new StringBuilder();

            dockerClient.logContainerCmd(containerName)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(lines)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            logs.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    })
                    .awaitCompletion(5, TimeUnit.SECONDS);

            return logs.toString();

        } catch (Exception e) {
            log.error("Failed to fetch logs for {}", containerName, e);
            return "Error fetching logs: " + e.getMessage();
        }
    }

    /**
     * 컨테이너 실시간 로그 스트리밍 (SSE)
     */
    @GetMapping(value = "/{containerName}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String containerName) {
        log.info("Starting log stream for container: {}", containerName);

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        try {
            // 먼저 최근 50줄 전송
            sendRecentLogs(emitter, containerName, 50);

            // 실시간 스트리밍 시작
            LogContainerCmd logCmd = dockerClient.logContainerCmd(containerName)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .withTail(0); // 최근 로그는 이미 보냈으므로 0

            ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        String logLine = new String(frame.getPayload(), StandardCharsets.UTF_8);
                        emitter.send(SseEmitter.event()
                                .data(logLine)
                                .name("log"));
                    } catch (IOException e) {
                        log.error("Error sending log line", e);
                        emitter.completeWithError(e);
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    log.error("Error in log stream", throwable);
                    emitter.completeWithError(throwable);
                }

                @Override
                public void onComplete() {
                    log.info("Log stream completed for {}", containerName);
                    emitter.complete();
                }
            };

            logCmd.exec(callback);

            // 연결 종료 시 callback 정리
            emitter.onCompletion(() -> {
                try {
                    callback.close();
                } catch (Exception e) {
                    log.error("Error closing callback", e);
                }
            });

            emitter.onTimeout(() -> {
                log.warn("Log stream timeout for {}", containerName);
                try {
                    callback.close();
                } catch (Exception e) {
                    log.error("Error closing callback", e);
                }
            });

        } catch (Exception e) {
            log.error("Failed to start log stream for {}", containerName, e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    private void sendRecentLogs(SseEmitter emitter, String containerName, int lines) {
        try {
            dockerClient.logContainerCmd(containerName)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(lines)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            try {
                                String logLine = new String(frame.getPayload(), StandardCharsets.UTF_8);
                                emitter.send(SseEmitter.event()
                                        .data(logLine)
                                        .name("log"));
                            } catch (IOException e) {
                                log.error("Error sending initial log", e);
                            }
                        }
                    })
                    .awaitCompletion(3, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Failed to send recent logs", e);
        }
    }
}