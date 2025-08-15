package uz.nodir.ratelimitapigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "uz.nodir")
public class RateLimitApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimitApiGatewayApplication.class, args);
    }

}
