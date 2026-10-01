package com.tocketmaster.event;

import com.tocketmaster.common.redis.HoldManager;
import com.tocketmaster.common.redis.ReservedSeatIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EventServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventServiceApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public HoldManager holdManager(@Value("${redis.uri}") String redisUri) {
        return new HoldManager(redisUri);
    }

    @Bean
    public ReservedSeatIndex reservedSeatIndex(HoldManager holdManager) {
        return new ReservedSeatIndex(holdManager.connection());
    }
}
