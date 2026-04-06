package com.tanvir.video.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "pipelineExecutor")
    public Executor pipelineExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
