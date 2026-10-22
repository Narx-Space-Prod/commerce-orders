package dev.boutique.orders;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> rejected(ResponseStatusException error) {
        var response = ResponseEntity.status(error.getStatusCode());
        if (error.getStatusCode().value() == 429) response.header("Retry-After", "900");
        return response.body(Map.of("error", error.getReason() == null ? "request_rejected" : error.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String,String>> malformed() {
        return ResponseEntity.badRequest().body(Map.of("error","invalid_json"));
    }
}
