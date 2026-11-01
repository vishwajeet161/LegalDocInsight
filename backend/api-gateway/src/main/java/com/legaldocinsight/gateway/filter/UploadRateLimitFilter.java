package com.legaldocinsight.gateway.filter;

import io.netty.util.NetUtil;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Arrays;

@Component
final class UploadRateLimitFilter implements GlobalFilter, Ordered {
    private final UploadRateLimiter limiter;
    private final UploadRateLimiter statusLimiter;
    private final Clock clock;
    private final List<TrustedSubnet> trustedProxies;

    @Autowired
    UploadRateLimitFilter(
            @Value("${app.upload-rate-limit.per-ip:5}") int perIpLimit,
            @Value("${app.upload-rate-limit.global:30}") int globalLimit,
            @Value("${app.upload-rate-limit.window-seconds:3600}") long windowSeconds,
            @Value("${app.upload-rate-limit.max-clients:10000}") int maxClients,
            @Value("${app.upload-rate-limit.trusted-proxies:}") String trustedProxies,
            @Value("${app.status-rate-limit.per-ip:120}") int statusPerIp,
            @Value("${app.status-rate-limit.global:3000}") int statusGlobal,
            @Value("${app.status-rate-limit.window-seconds:60}") long statusWindowSeconds) {
        this(new UploadRateLimiter(perIpLimit, globalLimit, Duration.ofSeconds(windowSeconds), maxClients),
            new UploadRateLimiter(statusPerIp, statusGlobal, Duration.ofSeconds(statusWindowSeconds), maxClients),
            Clock.systemUTC(), Arrays.stream(trustedProxies.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList());
    }

    UploadRateLimitFilter(UploadRateLimiter limiter, Clock clock) {
        this(limiter, clock, List.of());
    }

    UploadRateLimitFilter(UploadRateLimiter limiter, Clock clock, List<String> trustedProxies) {
        this(limiter, new UploadRateLimiter(120, 3000, Duration.ofMinutes(1), 10000), clock, trustedProxies);
    }

    UploadRateLimitFilter(UploadRateLimiter limiter, UploadRateLimiter statusLimiter,
                          Clock clock, List<String> trustedProxies) {
        this.limiter = limiter;
        this.statusLimiter = statusLimiter;
        this.clock = clock;
        this.trustedProxies = trustedProxies.stream().map(TrustedSubnet::parse).toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (!exchange.getRequest().getURI().getPath().startsWith("/api/documents")
                || !(HttpMethod.POST.equals(method) || HttpMethod.GET.equals(method)
                     || HttpMethod.HEAD.equals(method) || HttpMethod.DELETE.equals(method))) {
            return chain.filter(exchange);
        }
        UploadRateLimiter budget = HttpMethod.POST.equals(method) ? limiter : statusLimiter;
        InetSocketAddress peer = exchange.getRequest().getRemoteAddress();
        if (peer == null || peer.getAddress() == null
                || !budget.tryAcquire(clientAddress(exchange, peer), clock.instant())) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            exchange.getResponse().getHeaders().set("Retry-After", Long.toString(budget.retryAfterSeconds()));
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private String clientAddress(ServerWebExchange exchange, InetSocketAddress peer) {
        String address = peer.getAddress().getHostAddress();
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 4096) return address;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 32) return address;
        for (int i = hops.length - 1; i >= 0 && isTrusted(address); i--) {
            byte[] parsed = NetUtil.createByteArrayFromIpAddressString(hops[i].trim());
            if (parsed == null) return peer.getAddress().getHostAddress();
            try {
                address = InetAddress.getByAddress(parsed).getHostAddress();
            } catch (UnknownHostException ex) {
                return peer.getAddress().getHostAddress();
            }
        }
        return address;
    }

    private boolean isTrusted(String address) {
        byte[] parsed = NetUtil.createByteArrayFromIpAddressString(address);
        return parsed != null && trustedProxies.stream().anyMatch(proxy -> proxy.matches(parsed));
    }

    private record TrustedSubnet(byte[] address, int prefix) {
        static TrustedSubnet parse(String input) {
            String[] pieces = input.split("/", -1);
            if (pieces.length > 2) throw new IllegalArgumentException("Invalid trusted proxy subnet");
            byte[] address = NetUtil.createByteArrayFromIpAddressString(pieces[0]);
            if (address == null) throw new IllegalArgumentException("Invalid trusted proxy address");
            int prefix = pieces.length == 1 ? address.length * 8 : Integer.parseInt(pieces[1]);
            if (prefix < 0 || prefix > address.length * 8) {
                throw new IllegalArgumentException("Invalid trusted proxy prefix");
            }
            return new TrustedSubnet(address, prefix);
        }

        boolean matches(byte[] candidate) {
            if (candidate.length != address.length) return false;
            int completeBytes = prefix / 8;
            for (int i = 0; i < completeBytes; i++) {
                if (address[i] != candidate[i]) return false;
            }
            int remainder = prefix % 8;
            if (remainder == 0) return true;
            int mask = 0xff << (8 - remainder);
            return (address[completeBytes] & mask) == (candidate[completeBytes] & mask);
        }
    }

    @Override
    public int getOrder() {
        return -10;
    }
}
