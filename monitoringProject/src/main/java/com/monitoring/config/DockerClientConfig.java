package com.monitoring.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Slf4j
public class DockerClientConfig {

    @Value("${docker.host}")
    private String dockerHost;

    @Bean
    public DockerClient dockerClient() {
        log.info("Initializing Docker client with host: {}", dockerHost);

        com.github.dockerjava.core.DockerClientConfig clientConfig =
                DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withDockerHost(dockerHost)
                        .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(clientConfig.getDockerHost())  // ✅ config → clientConfig
                .maxConnections(100)
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        DockerClient dockerClient = DockerClientImpl.getInstance(clientConfig, httpClient);  // ✅ config → clientConfig

        log.info("Docker client initialized successfully");
        return dockerClient;
    }
}