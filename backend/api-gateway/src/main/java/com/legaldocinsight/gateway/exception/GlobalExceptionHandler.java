package com.legaldocinsight.gateway.exception;


import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Order(-2)
@Component
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        
        if (exchange.getResponse().isCommitted()) {
            log.debug("Response already committed, cannot write error for path={}",
                exchange.getRequest().getURI().getPath());
            return Mono.empty();
        }

        HttpStatus status;
        String message;

        if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            message = rse.getReason() != null ? rse.getReason() : "Request failed";
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "An unexpected error occurred";
            log.error("Unhandled gateway exception for path={}: {}",
                exchange.getRequest().getURI().getPath(), ex.getMessage(), ex);
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
            "{\"status\": %d, \"error\": \"%s\", \"message\": \"%s\"}",
            status.value(), status.getReasonPhrase(), message
        );

        var buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}