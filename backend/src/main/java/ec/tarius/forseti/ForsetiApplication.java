package ec.tarius.forseti;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableRetry
@ConfigurationPropertiesScan("ec.tarius.forseti")
public class ForsetiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ForsetiApplication.class, args);
    }
}
