package com.tocketmaster.booking;

import com.tocketmaster.common.redis.HoldManager;
import com.tocketmaster.common.redis.ReservedSeatIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
@EnableScheduling
public class BookingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public HoldManager holdManager(@Value("${redis.uri}") String redisUri) {
        return new HoldManager(redisUri);
    }

    @Bean
    public ReservedSeatIndex reservedSeatIndex(HoldManager holdManager) {
        return new ReservedSeatIndex(holdManager.connection());
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager ptm) {
        return new TransactionTemplate(ptm);
    }
}
