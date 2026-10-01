package com.tocketmaster.search;

import com.tocketmaster.common.redis.HoldManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public HoldManager holdManager(@Value("${redis.uri}") String redisUri) {
        return new HoldManager(redisUri);
    }
}
