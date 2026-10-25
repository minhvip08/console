package com.simi.console.feature.shopee;

import com.simi.common.shopee.ShopeeException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

@RestControllerAdvice
class ShopeeExceptionHandler {
    @ExceptionHandler(ShopeeException.class)
    ResponseEntity<Map<String, String>> handle(ShopeeException e) {
        return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage(), "mode", "mock-only"));
    }
}
