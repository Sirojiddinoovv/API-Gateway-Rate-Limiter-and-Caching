package uz.nodir.ratelimitapigateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import uz.nodir.ratelimitapigateway.model.property.ResponseCacheProperty;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

@Slf4j
@Component
public class ResponseCacheGatewayFilterFactory extends AbstractGatewayFilterFactory<ResponseCacheProperty> implements Ordered {

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper om = new ObjectMapper();
    private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {
    };

    public ResponseCacheGatewayFilterFactory(ReactiveStringRedisTemplate redis) {
        super(ResponseCacheProperty.class);
        this.redis = redis;
    }


    @Override
    public int getOrder() {
        return -1;
    }

    @Override
    public GatewayFilter apply(ResponseCacheProperty cfg) {
        log.info("Taken config for caching: {}", cfg);

        GatewayFilter core = (exchange, chain) -> {
            if (exchange.getRequest().getMethod() != HttpMethod.GET ||
                    "no-cache".equalsIgnoreCase(exchange.getRequest().getHeaders().getCacheControl())) {
                return chain.filter(exchange);
            }

            String cacheKey = buildKey(exchange, cfg);
            return redis.opsForValue()
                    .get(cacheKey)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(opt -> {
                        if (opt.isPresent()) {
                            // HIT
                            String json = opt.get();
                            try {
                                var m = om.readValue(json, MAP);

                                var res = exchange.getResponse();
                                log.info("Cached response: {}", json);

                                res.setStatusCode(HttpStatus.valueOf(Integer.parseInt(m.get("status"))));
                                om.readValue(m.get("headers"), MAP).forEach(res.getHeaders()::addIfAbsent);
                                if (cfg.isAddCacheHeader()) res.getHeaders().set("X-Cache", "HIT");
                                byte[] body = Base64.getDecoder().decode(m.get("body"));
                                return res.writeWith(Mono.just(res.bufferFactory().wrap(body)));
                            } catch (Exception e) {
                                return chain.filter(decorate(exchange, cfg, cacheKey));
                            }
                        } else {
                            // MISS
                            log.info("No cached response");
                            return chain.filter(decorate(exchange, cfg, cacheKey));
                        }
                    });
        };

        return new OrderedGatewayFilter(core, NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1);
    }

    private ServerWebExchange decorate(ServerWebExchange exchange, ResponseCacheProperty cfg, String cacheKey) {
        var original = exchange.getResponse();
        log.info("Original response: {}", original);
        var factory = original.bufferFactory();

        var decorated = new ServerHttpResponseDecorator(original) {
            @Override
            @NonNull
            public Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
                log.info("Received body: {}", body);
                Flux<DataBuffer> flux = Flux.from(body);

                return DataBufferUtilsEx.aggregate(flux, cfg.getMaxBytes())
                        .flatMap(agg -> {
                            byte[] bytes = agg.bytes;

                            int st = getStatusCode() != null ? getStatusCode().value() : 200;
                            var ct = getHeaders().getContentType();
                            boolean okStatus = !cfg.isOnly200() || st == 200;
                            boolean okJson = !cfg.isJsonOnly() || (ct != null && MediaType.APPLICATION_JSON.isCompatibleWith(ct));

                            Mono<Void> writeBody = super.writeWith(Mono.just(factory.wrap(bytes)));

                            Mono<Boolean> saveMono = Mono.just(false);

                            if (okStatus && okJson) {
                                log.info("Status is OK and body is JSON");
                                try {
                                    Map<String, String> hs = new HashMap<>();
                                    getHeaders().forEach((k, v) -> {
                                        if (!v.isEmpty()) hs.put(k, v.get(0));
                                    });

                                    Map<String, Object> record = new HashMap<>();
                                    record.put("status", st);
                                    record.put("headers", om.writeValueAsString(hs));
                                    record.put("body", Base64.getEncoder().encodeToString(bytes));
                                    String json = om.writeValueAsString(record);

                                    saveMono = redis.opsForValue()
                                            .set(cacheKey, json, Duration.ofSeconds(cfg.getTtlSeconds()))
                                            .doOnSuccess(ok -> log.debug("Cache set {} ttl={}s", cacheKey, cfg.getTtlSeconds()))
                                            .doOnError(err -> log.warn("Cache write error {}: {}", cacheKey, err.toString()));
                                } catch (Exception e) {
                                    log.warn("Cache pack error: {}", e.getMessage());
                                }
                            }

                            if (cfg.isAddCacheHeader()) getHeaders().set("X-Cache", "MISS");

                            return writeBody
                                    .then(saveMono)
                                    .then();
                        });
            }

            @Override
            @NonNull
            public Mono<Void> writeAndFlushWith(
                    @NonNull Publisher<? extends Publisher<? extends DataBuffer>> body) {
                return writeWith(Flux.from(body)
                        .flatMapSequential(Function.identity()));
            }
        };

        return exchange.mutate().response(decorated).build();
    }

    private String buildKey(ServerWebExchange ex, ResponseCacheProperty cfg) {
        var req = ex.getRequest();
        StringBuilder sb = new StringBuilder("cache:");
        sb.append(req.getMethod()).append('|');
        sb.append(req.getPath().pathWithinApplication().value()).append('|');

        var params = new TreeMap<>(req.getQueryParams().toSingleValueMap());
        if (!params.isEmpty()) sb.append(params);

        if (cfg.isKeyByIntegrator()) {
            String integrator = Optional.ofNullable(req.getHeaders().getFirst("X-Integrator-Id"))
                    .map(String::trim).filter(s -> !s.isEmpty()).orElse("no-int");
            sb.append("|int=").append(integrator.toLowerCase(Locale.ROOT));
        }

        URI target = ex.getAttribute(GATEWAY_REQUEST_URL_ATTR);
        if (target != null) sb.append("|t=").append(target.getPath());
        var cacheType = sb.toString();

        log.info("Response will be cached with type: {}", cacheType);
        return cacheType;
    }

    static class DataBufferUtilsEx {
        static Mono<Agg> aggregate(Flux<DataBuffer> flux, int maxBytes) {
            DataBufferFactory[] f = new DataBufferFactory[1];
            return flux.reduce(new Agg(new byte[0], null), (agg, buf) -> {
                if (agg.factory == null) agg.factory = buf.factory();
                byte[] chunk = new byte[buf.readableByteCount()];
                buf.read(chunk);
                org.springframework.core.io.buffer.DataBufferUtils.release(buf);

                if (agg.bytes.length + chunk.length > maxBytes)
                    throw new IllegalStateException("Response too large to cache");

                byte[] concat = Arrays.copyOf(agg.bytes, agg.bytes.length + chunk.length);
                System.arraycopy(chunk, 0, concat, agg.bytes.length, chunk.length);
                agg.bytes = concat;
                return agg;
            });
        }

        static class Agg {
            byte[] bytes;
            DataBufferFactory factory;

            Agg(byte[] b, DataBufferFactory f) {
                this.bytes = b;
                this.factory = f;
            }
        }
    }
}
