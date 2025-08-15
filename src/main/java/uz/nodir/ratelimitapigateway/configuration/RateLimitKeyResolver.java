package uz.nodir.ratelimitapigateway.configuration;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Author:Nodir
 * User:smart
 * Date:13.08.2025
 * Time:8:26 AM
 */

@Slf4j
@Configuration
public class RateLimitKeyResolver {

    private final static String IP_ADDRESS = "X-Real-IP";

    @PostConstruct
    public void init() {
        log.info("RateLimitKeyResolver initialized");
    }

    @Bean
    KeyResolver redisCustomKeyResolver() {
        return exchange -> Mono.justOrEmpty(
                exchange.getRequest().getHeaders().getFirst(IP_ADDRESS)
        );
    }

}
