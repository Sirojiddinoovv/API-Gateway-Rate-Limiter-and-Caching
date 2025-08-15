package uz.nodir.gateway.filters;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import uz.nodir.ratelimitapigateway.model.property.JsonRateLimitProperty;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

@Slf4j
@Component
public class JsonRateLimiterGatewayFilterFactory
        extends AbstractGatewayFilterFactory<JsonRateLimitProperty>
        implements Ordered {

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final static String IP_ADDRESS = "X-Real-IP";


    @Override
    public String name() {
        return super.name();
    }


    public JsonRateLimiterGatewayFilterFactory(ReactiveStringRedisTemplate redis) {
        super(JsonRateLimitProperty.class);
        this.redis = redis;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public GatewayFilter apply(JsonRateLimitProperty property) {
        return (exchange, chain) -> {
            var h = exchange.getRequest().getHeaders();
            boolean hasIntegrator = h.getFirst(IP_ADDRESS) != null && !Objects.requireNonNull(h.getFirst(IP_ADDRESS)).isBlank();

            if (property.isRequireIntegratorId() && !hasIntegrator) {
                log.error("Missing {} field in header for request path: {}", IP_ADDRESS, exchange.getRequest().getPath());

                return writeCustom(exchange,
                        property.getMissingIntegratorStatus(),
                        property.getMissingIntegratorCode(),
                        property.getMissingIntegratorMessage());
            }

            return resolveKey(exchange)
                    .flatMap(key -> {
                        log.info("Received request from IP: {} by path: {}", key, exchange.getRequest().getPath());

                        if (key == null || key.isBlank()) {
                            return writeJson(
                                    exchange,
                                    property.getMissingIntegratorStatus(),
                                    property.getMissingIntegratorCode(),
                                    property.getMissingIntegratorMessage(),
                                    null,
                                    0);
                        }

                        String redisKey = "redisRateLimiter" + ":" + key;

                        return redis
                                .opsForValue()
                                .increment(redisKey)
                                .flatMap(count -> {
                                    if (count != null && count == 1L) {
                                        return redis.expire(redisKey, Duration.ofSeconds(property.getWindowSeconds()))
                                                .thenReturn(count);
                                    }

                                    return Mono.just(count != null ? count : 0);
                                })
                                .flatMap(count -> {
                                    long used = count == null ? 0 : count;
                                    long remaining = Math.max(0, property.getLimit() - used);
                                    log.warn("Remaining limit: {}", remaining);

                                    if (used > property.getLimit()) {
                                        return redis
                                                .getExpire(redisKey)
                                                .switchIfEmpty(Mono.just(Duration.ZERO))
                                                .flatMap(ttl -> writeJson(
                                                        exchange,
                                                        property.getStatusCode(),
                                                        property.getFailedCode(),
                                                        property.getFailedMessage(),
                                                        key,
                                                        Math.max(0, ttl.getSeconds())));
                                    }

                                    return chain.filter(exchange);
                                });
                    })
                    .doFinally(
                            s -> {
                                final URI target = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
                                final Set<URI> originals = exchange.getAttributeOrDefault(GATEWAY_ORIGINAL_REQUEST_URL_ATTR, Collections.emptySet());
                                final Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);

                                log.info("GW route={} original={} -> target={} status={}",
                                        route != null ? route.getId() : "?",
                                        originals.stream().findFirst().orElse(exchange.getRequest().getURI()),
                                        target,
                                        exchange.getResponse().getStatusCode());

                            }
                    );
        }
                ;
    }

    private Mono<Void> writeCustom(ServerWebExchange ex, int status, String code, String message) {
        var res = ex.getResponse();
        res.setStatusCode(HttpStatus.valueOf(status));
        res.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var map = new HashMap<String, Object>();
        map.put("code", code);
        map.put("message", message);
        map.put("status", status);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(map);
        } catch (Exception e) {
            bytes = ("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        return res.writeWith(Mono.just(res.bufferFactory().wrap(bytes)));
    }

    private Mono<String> resolveKey(ServerWebExchange ex) {
        var cfgAttr = ex.getAttributeOrDefault("rl.precomputedKey", new Object());
        if (cfgAttr instanceof String s && !s.isBlank()) return Mono.just(s);

        var h = ex.getRequest().getHeaders();
        String integrator = firstNonBlank(
                h.getFirst(IP_ADDRESS),
                null);
        if (integrator != null) return Mono.just(integrator.trim().toLowerCase());

        String realIp = firstNonBlank(h.getFirst("X-Real-IP"));
        if (realIp != null) return Mono.just("ip:" + realIp.trim());

        String xff = firstNonBlank(h.getFirst("X-Forwarded-For"));
        if (xff != null) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty()) return Mono.just("ip:" + first);
        }

        var remote = ex.getRequest().getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return Mono.just("ip:" + remote.getAddress().getHostAddress());
        }
        return Mono.just("ip:unknown");
    }

    private String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }


    private Mono<Void> writeJson(ServerWebExchange ex, int status, String code, String message,
                                 String key, long retryAfterSeconds) {
        ServerHttpResponse response = ex.getResponse();
        response.setStatusCode(HttpStatus.valueOf(status));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("status", status);
        if (retryAfterSeconds >= 0) body.put("retryAfterSeconds", retryAfterSeconds);
        if (key != null) body.put("key", key);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            String fallback = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
            bytes = fallback.getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
}
