package com.cpms.community.amenity.service;

import com.cpms.community.amenity.AmenityErrors;
import com.cpms.community.amenity.AmenityProperties;
import com.cpms.community.amenity.entity.Amenity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Component
public class ImageStorage {
    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private final Path root;

    public ImageStorage(AmenityProperties properties) {
        this.root = Path.of(properties.uploadDir() == null ? "./uploads/amenities" : properties.uploadDir());
    }

    public void save(Amenity amenity, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Image file is required.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Image must be a JPG or PNG file of at most 5 MB.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Image upload failed. The previous image was kept.");
        }
        String contentType = sniff(file.getContentType(), bytes);
        String extension = contentType.equals("image/png") ? ".png" : ".jpg";
        // A new name per upload: concurrent uploads never share a file, and the old image stays
        // readable until the database change commits.
        String filename = "display-" + UUID.randomUUID() + extension;
        Path dir = root.resolve(String.valueOf(amenity.id));
        Path destination = dir.resolve(filename);
        try {
            Files.createDirectories(dir);
            Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            remove(destination);
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Image upload failed. The previous image was kept.");
        }
        Path previous = amenity.imageFilename == null ? null : dir.resolve(amenity.imageFilename);
        afterTransaction(() -> remove(previous), () -> remove(destination));
        amenity.imageFilename = filename;
        amenity.imageContentType = contentType;
    }

    public void delete(Amenity amenity) {
        if (amenity.imageFilename == null) {
            return;
        }
        Path previous = root.resolve(String.valueOf(amenity.id)).resolve(amenity.imageFilename);
        afterTransaction(() -> remove(previous), () -> {});
        amenity.imageFilename = null;
        amenity.imageContentType = null;
    }

    /** Runs onCommit once the surrounding transaction commits, onRollback if it does not. */
    private static void afterTransaction(Runnable onCommit, Runnable onRollback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            onCommit.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                (status == STATUS_COMMITTED ? onCommit : onRollback).run();
            }
        });
    }

    private static void remove(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // an orphaned file does not affect the stored image
        }
    }

    public byte[] read(Amenity amenity) {
        if (amenity.imageFilename == null) {
            throw AmenityErrors.fail(HttpStatus.NOT_FOUND, "Image not found.");
        }
        try {
            return Files.readAllBytes(root.resolve(String.valueOf(amenity.id)).resolve(amenity.imageFilename));
        } catch (IOException e) {
            throw AmenityErrors.fail(HttpStatus.NOT_FOUND, "Image not found.");
        }
    }

    public String publicUrl(Amenity amenity) {
        if (amenity.imageFilename == null) {
            return null;
        }
        // The file name changes with every upload, so it doubles as a cache-busting version.
        return "/api/resident/amenities/" + amenity.id + "/image?v=" + amenity.imageFilename;
    }

    private static String sniff(String declared, byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return "image/png";
        }
        if ("image/jpeg".equalsIgnoreCase(declared) || "image/jpg".equalsIgnoreCase(declared) || "image/png".equalsIgnoreCase(declared)) {
            throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Image must be a JPG or PNG file of at most 5 MB.");
        }
        throw AmenityErrors.fail(HttpStatus.BAD_REQUEST, "Image must be a JPG or PNG file of at most 5 MB.");
    }
}
