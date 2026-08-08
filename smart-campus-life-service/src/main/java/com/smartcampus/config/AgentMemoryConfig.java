package com.smartcampus.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Agent 记忆后台任务配置，独立于 RAG 开关，避免摘要任务占用 Web 请求线程。 */
@Configuration
@EnableAsync
public class AgentMemoryConfig {

    /**
     * 有界线程池用于增量摘要。队列满时由调用线程执行，形成自然背压，避免高峰期无限堆积任务。
     */
    @Bean("agentMemoryTaskExecutor")
    public Executor agentMemoryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-memory-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
