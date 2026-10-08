package com.cpms.community.amenity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import java.util.Map;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages="com.cpms.community.amenity.controller")
public class AmenityApiErrors {
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class,
                       org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<?> conflict(Exception e) {
        return ResponseEntity.status(409).body(Map.of("message","This reservation changed or the slot is no longer available. Refresh and try again."));
    }
}
