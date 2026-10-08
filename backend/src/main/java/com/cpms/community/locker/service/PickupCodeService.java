package com.cpms.community.locker.service;

import com.cpms.community.locker.entity.Parcel;
import com.cpms.community.locker.entity.PickupCredential;
import com.cpms.community.locker.enums.PickupCredentialStatus;
import com.cpms.community.locker.repository.PickupCredentialRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PickupCodeService {

    public record IssuedCode(
            String rawCode,
            PickupCredential credential
    ) {}

    private final PickupCredentialRepository credentials;
    private final byte[] secret;

    public PickupCodeService(
            PickupCredentialRepository credentials,
            @Value("${locker.pickup-code-secret}") String secret
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "locker.pickup-code-secret must be configured."
            );
        }

        this.credentials = credentials;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public IssuedCode createCode(Parcel parcel) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String nonce = UUID.randomUUID().toString();
            String rawCode = deriveCode(nonce);
            String codeHash = hash(rawCode);

            if (credentials.existsByCodeHash(codeHash)) {
                continue;
            }

            PickupCredential credential = new PickupCredential();
            credential.parcel = parcel;
            credential.codeNonce = nonce;
            credential.codeHash = codeHash;
            credential.status = PickupCredentialStatus.ACTIVE;
            credential.expiresAt = parcel.expiresAt;

            credentials.saveAndFlush(credential);
            return new IssuedCode(rawCode, credential);
        }

        throw new IllegalStateException(
                "Unable to generate a unique pickup code."
        );
    }

    @Transactional
    public IssuedCode regenerateCode(Parcel parcel) {
        List<PickupCredential> activeCredentials =
                credentials.lockByParcelIdAndStatus(
                        parcel.id,
                        PickupCredentialStatus.ACTIVE
                );

        Instant now = Instant.now();

        for (PickupCredential credential : activeCredentials) {
            credential.status = PickupCredentialStatus.INVALIDATED;
            credential.invalidatedAt = now;
        }

        return createCode(parcel);
    }

    private String deriveCode(String nonce) {
        String digest = hash("pickup-code-seed:" + nonce);
        long value = Long.parseLong(digest.substring(0, 12), 16);
        return String.format(Locale.ROOT, "%06d", value % 1_000_000L);
    }

    public String codeForDelivery(PickupCredential credential) {
        if (credential.codeNonce == null
                || credential.status != PickupCredentialStatus.ACTIVE
                || !Instant.now().isBefore(credential.expiresAt)) {
            throw new IllegalStateException(
                    "No active pickup code is available."
            );
        }

        String code = deriveCode(credential.codeNonce);
        if (!hash(code).equals(credential.codeHash)) {
            throw new IllegalStateException(
                    "Pickup code secret has changed."
            );
        }

        return code;
    }

    public String hash(String rawCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));

            byte[] result = mac.doFinal(
                    rawCode.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(result);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                    "Unable to hash pickup code.",
                    exception
            );
        }
    }
}