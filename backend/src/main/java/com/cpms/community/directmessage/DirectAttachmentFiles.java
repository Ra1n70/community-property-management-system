package com.cpms.community.directmessage;

import com.cpms.community.AccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Checks and stores message attachments: JPG/PNG photos (re-encoded as PNG, which drops hidden metadata)
 * and PDF documents, at most 3 files of 5 MB each. Files written by a transaction that rolls back are removed.
 */
@Component
public class DirectAttachmentFiles {
    public static final int MAX_FILES = 3;
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    /** A checked file ready to be written. */
    public record Checked(String filename, String contentType, byte[] bytes) {}

    private final Path root;

    public DirectAttachmentFiles(@Value("${messages.upload-dir:./uploads/messages}") String dir) {
        root = Path.of(dir).toAbsolutePath().normalize();
    }

    public List<Checked> check(List<MultipartFile> files) {
        List<MultipartFile> present = files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (present.size() > MAX_FILES) throw bad("Attach at most 3 files.");
        List<Checked> checked = new ArrayList<>();
        for (MultipartFile file : present) {
            if (file.getSize() > MAX_BYTES) throw bad("Each file must be 5 MB or smaller.");
            byte[] bytes;
            try { bytes = file.getBytes(); } catch (IOException e) { throw bad("A file could not be read. Try again."); }
            String name = cleanName(file.getOriginalFilename());
            if (bytes.length > 5 && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) {
                checked.add(new Checked(withExtension(name, ".pdf"), "application/pdf", bytes));
            } else {
                checked.add(new Checked(withExtension(name, ".png"), "image/png", reencode(bytes)));
            }
        }
        return checked;
    }

    private static byte[] reencode(byte[] bytes) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException();
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw new IOException();
                reader.setInput(input);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 16_000_000) throw new IOException();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ImageIO.write(reader.read(0), "png", output);
                return output.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException e) {
            throw bad("Attach JPG or PNG photos (up to 16 megapixels) or PDF files.");
        }
    }

    /** Writes the files and returns their storage keys, in order. */
    public List<String> write(List<Checked> files) {
        List<Path> written = new ArrayList<>();
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) cleanup(written); }
            });
        List<String> keys = new ArrayList<>();
        try {
            Files.createDirectories(root);
            for (Checked file : files) {
                String key = UUID.randomUUID() + (file.contentType().equals("application/pdf") ? ".pdf" : ".png");
                Path path = root.resolve(key);
                written.add(path);
                Files.write(path, file.bytes(), StandardOpenOption.CREATE_NEW);
                keys.add(key);
            }
        } catch (IOException e) {
            cleanup(written);
            throw AccountService.fail(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to save the attachment. Please try again.");
        }
        return keys;
    }

    public byte[] read(String key) {
        if (!key.matches("[a-f0-9-]{36}\\.(png|pdf)")) throw AccountService.fail(HttpStatus.NOT_FOUND, "Attachment not found.");
        try { return Files.readAllBytes(root.resolve(key)); }
        catch (IOException e) { throw AccountService.fail(HttpStatus.NOT_FOUND, "Attachment not found."); }
    }

    private void cleanup(List<Path> paths) {
        for (Path path : paths) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static String cleanName(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "").strip();
        if (name.isEmpty()) name = "attachment";
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static String withExtension(String name, String extension) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + extension;
    }

    private static RuntimeException bad(String message) { return AccountService.fail(HttpStatus.BAD_REQUEST, message); }
}
