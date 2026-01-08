package com.monitoring.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "docker")
public class DockerProperties {

    private String host;
    private List<String> targetContainers;
}