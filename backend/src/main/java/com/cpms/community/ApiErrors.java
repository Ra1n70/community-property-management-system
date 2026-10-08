package com.cpms.community;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> response(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()));}
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var error=e.getBindingResult().getFieldErrors().getFirst();
        return ResponseEntity.badRequest().body(Map.of("message",error.getField()+": "+error.getDefaultMessage()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> duplicate() {return ResponseEntity.status(409).body(Map.of("message","This email is already registered."));}
}
