package com.tocketmaster.queue;

import com.tocketmaster.common.redis.HoldManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableScheduling
public class QueueServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueServiceApplication.class, args);
    }

    /** Shared Redis connection (the queue never uses hold locks, just the connection). */
    @Bean(destroyMethod = "close")
    public HoldManager redis(@Value("${redis.uri}") String redisUri) {
        return new HoldManager(redisUri);
    }
}
