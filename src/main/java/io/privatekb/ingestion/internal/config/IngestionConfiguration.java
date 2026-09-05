package io.privatekb.ingestion.internal.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties({IngestionProperties.class, IndexingProperties.class, OcrProperties.class})
class IngestionConfiguration {

    private static final int MAX_PENDING_DOCUMENT_TASKS = 10_000;

    @Bean(name = "ingestionTaskExecutor")
    ThreadPoolTaskExecutor ingestionTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(MAX_PENDING_DOCUMENT_TASKS);
        executor.setThreadNamePrefix("privatekb-ingestion-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean(name = "indexingTaskExecutor")
    ThreadPoolTaskExecutor indexingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(MAX_PENDING_DOCUMENT_TASKS);
        executor.setThreadNamePrefix("privatekb-indexing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService tikaParserExecutor() {
        return Executors.newFixedThreadPool(
                1,
                Thread.ofPlatform().name("privatekb-tika-", 0).factory()
        );
    }

    @Bean(name = "ocrTaskExecutor")
    ThreadPoolTaskExecutor ocrTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(MAX_PENDING_DOCUMENT_TASKS);
        executor.setThreadNamePrefix("privatekb-ocr-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService ocrParserExecutor() {
        return Executors.newFixedThreadPool(
                1,
                Thread.ofPlatform().name("privatekb-ocr-parser-", 0).factory()
        );
    }
}
