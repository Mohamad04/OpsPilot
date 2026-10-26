package com.opspilot.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitoringProperties.class)
public class MonitoringConfiguration {
    @Bean public Clock monitoringClock() { return Clock.systemUTC(); }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService healthCheckDeadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().name("health-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "opspilot.monitoring.enabled", havingValue = "true", matchIfMissing = true)
    static class Scheduling {
        // Never run blocking check rounds on the executor responsible for closing timed-out sockets.
        @Bean public ThreadPoolTaskScheduler taskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("health-scheduler-");
            scheduler.setRemoveOnCancelPolicy(true);
            return scheduler;
        }
    }
}
