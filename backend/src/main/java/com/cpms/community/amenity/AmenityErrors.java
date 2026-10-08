package com.cpms.community.amenity;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AmenityErrors {
    private AmenityErrors() {}

    public static ResponseStatusException fail(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    public static ResponseStatusException notFound() {
        return fail(HttpStatus.NOT_FOUND, "Amenity not found.");
    }

    public static ResponseStatusException reservationNotFound() {
        return fail(HttpStatus.NOT_FOUND, "Reservation not found.");
    }
}
