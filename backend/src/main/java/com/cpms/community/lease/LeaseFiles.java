package com.cpms.community.lease;

import com.cpms.community.AccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Stores lease PDFs (5 MB at most) outside the database. A file written by a rolled-back upload is removed. */
@Component
public class LeaseFiles {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    private final Path root;

    public LeaseFiles(@Value("${leases.upload-dir:./uploads/leases}") String dir) { root = Path.of(dir).toAbsolutePath().normalize(); }

    public byte[] check(MultipartFile file) {
        if (file == null || file.isEmpty()) throw bad("Choose a PDF file.");
        if (file.getSize() > MAX_BYTES) throw bad("The PDF must be 5 MB or smaller.");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (IOException e) { throw bad("The file could not be read. Try again."); }
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw bad("Upload a PDF document.");
        return bytes;
    }

    public String write(byte[] bytes) {
        String key = UUID.randomUUID() + ".pdf";
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) remove(key); }
            });
        try {
            Files.createDirectories(root);
            Files.write(root.resolve(key), bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            remove(key);
            throw AccountService.fail(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to save the document. Please try again.");
        }
        return key;
    }

    public byte[] read(String key) {
        if (!valid(key)) throw AccountService.fail(HttpStatus.NOT_FOUND, "Document not found.");
        try { return Files.readAllBytes(root.resolve(key)); }
        catch (IOException e) { throw AccountService.fail(HttpStatus.NOT_FOUND, "Document not found."); }
    }

    /** Removes the file once the deleting transaction commits. */
    public void removeAfterCommit(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { remove(key); }
            });
        else remove(key);
    }

    private static boolean valid(String key) { return key.matches("[a-f0-9-]{36}\\.pdf"); }

    private void remove(String key) {
        if (!valid(key)) return;
        try { Files.deleteIfExists(root.resolve(key)); } catch (IOException ignored) { }
    }

    private static RuntimeException bad(String message) { return AccountService.fail(HttpStatus.BAD_REQUEST, message); }
}
