package com.cpms.community.lease;

import com.cpms.community.Account;
import com.cpms.community.AccountRepository;
import com.cpms.community.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Lease documents: a manager uploads PDFs for a resident of the community; only that resident and the
 * community's managers can list or download them. Payments keep their own records and permissions.
 */
@RestController
@RequestMapping("/api/lease-documents")
@Transactional
public class LeaseDocumentApi {
    public record Upload(@NotNull Long residentId, @NotBlank @Size(max = 160) String title, LocalDate startsOn, LocalDate endsOn) {}
    public record DocumentView(Long id, Long residentId, String residentName, String room, String title, LocalDate startsOn,
                               LocalDate endsOn, String filename, long sizeBytes, String uploadedByName, Instant uploadedAt) {
        static DocumentView of(LeaseDocument d) {
            return new DocumentView(d.id, d.residentId, d.residentName, d.room, d.title, d.startsOn, d.endsOn, d.filename,
                    d.sizeBytes, d.uploadedByName, d.uploadedAt);
        }
    }

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final LeaseDocumentRepository documents;
    private final LeaseFiles files;

    public LeaseDocumentApi(AccountService accountService, AccountRepository accounts, LeaseDocumentRepository documents, LeaseFiles files) {
        this.accountService = accountService; this.accounts = accounts; this.documents = documents; this.files = files;
    }

    private Account member(Authentication auth) {
        Account a = accountService.current(auth.getName());
        if (a.status != Account.Status.APPROVED || a.role == Account.Role.PROVIDER)
            throw AccountService.fail(HttpStatus.FORBIDDEN, "Lease documents are available to approved residents and managers.");
        return a;
    }

    private Account manager(Authentication auth) {
        Account a = member(auth);
        if (a.role != Account.Role.MANAGER) throw AccountService.fail(HttpStatus.FORBIDDEN, "Manager access required.");
        return a;
    }

    private static RuntimeException missing() { return AccountService.fail(HttpStatus.NOT_FOUND, "Document not found."); }

    /** Residents get their own documents; managers get one resident's (residentId) or the whole community's. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<DocumentView> list(Authentication auth, @RequestParam(required = false) Long residentId) {
        Account a = member(auth);
        List<LeaseDocument> found = a.role == Account.Role.RESIDENT
                ? documents.findByCommunityAndResidentIdOrderByUploadedAtDesc(a.community, a.id)
                : residentId != null ? documents.findByCommunityAndResidentIdOrderByUploadedAtDesc(a.community, residentId)
                : documents.findByCommunityOrderByUploadedAtDesc(a.community);
        return found.stream().map(DocumentView::of).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView upload(Authentication auth, @Valid @RequestPart("request") Upload input,
                               @RequestPart(value = "file", required = false) MultipartFile file) {
        Account a = manager(auth);
        Account resident = accounts.findById(input.residentId())
                .filter(r -> r.community.equals(a.community) && r.role == Account.Role.RESIDENT && r.status == Account.Status.APPROVED)
                .orElseThrow(() -> AccountService.fail(HttpStatus.BAD_REQUEST, "Choose an approved resident in your community."));
        if (input.startsOn() != null && input.endsOn() != null && input.endsOn().isBefore(input.startsOn()))
            throw AccountService.fail(HttpStatus.BAD_REQUEST, "The end date must be on or after the start date.");
        byte[] bytes = files.check(file);
        LeaseDocument d = new LeaseDocument();
        d.community = a.community; d.residentId = resident.id; d.residentName = resident.name; d.room = resident.room;
        d.title = input.title().strip(); d.startsOn = input.startsOn(); d.endsOn = input.endsOn();
        d.filename = filename(file.getOriginalFilename()); d.sizeBytes = bytes.length;
        d.uploadedBy = a.id; d.uploadedByName = a.name;
        d.storageKey = files.write(bytes);
        return DocumentView.of(documents.saveAndFlush(d));
    }

    @GetMapping("/{id}/file")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> download(Authentication auth, @PathVariable Long id) {
        Account a = member(auth);
        LeaseDocument d = documents.findByIdAndCommunity(id, a.community).orElseThrow(LeaseDocumentApi::missing);
        if (a.role == Account.Role.RESIDENT && !d.residentId.equals(a.id)) throw missing();
        return ResponseEntity.ok()
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", ContentDisposition.attachment().filename(d.filename, StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(files.read(d.storageKey));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable Long id) {
        Account a = manager(auth);
        LeaseDocument d = documents.findByIdAndCommunity(id, a.community).orElseThrow(LeaseDocumentApi::missing);
        documents.delete(d);
        files.removeAfterCommit(d.storageKey);
    }

    private static String filename(String original) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|:*?]", "").strip();
        if (name.isEmpty()) name = "lease";
        if (!name.toLowerCase().endsWith(".pdf")) name = name + ".pdf";
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }
}
