package com.bank.wid.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${wid.thread-pool.core-size:50}")
    private int corePoolSize;

    @Value("${wid.thread-pool.max-size:200}")
    private int maxPoolSize;

    @Value("${wid.thread-pool.queue-capacity:50000}")
    private int queueCapacity;

    private final AtomicLong callerRunsCount = new AtomicLong(0);

    @Bean("alertProcessingExecutor")
    public Executor alertProcessingExecutor(MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("alert-proc-");
        executor.setRejectedExecutionHandler((r, e) -> {
            callerRunsCount.incrementAndGet();
            new ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(r, e);
        });
        executor.initialize();

        // Register metrics gauges
        meterRegistry.gauge("wid.threadpool.queue.size", executor, e -> e.getThreadPoolExecutor().getQueue().size());
        meterRegistry.gauge("wid.threadpool.active.threads", executor, e -> e.getThreadPoolExecutor().getActiveCount());
        meterRegistry.gauge("wid.threadpool.caller.runs.count", callerRunsCount, AtomicLong::get);

        return executor;
    }

    public AtomicLong getCallerRunsCount() {
        return callerRunsCount;
    }
}
