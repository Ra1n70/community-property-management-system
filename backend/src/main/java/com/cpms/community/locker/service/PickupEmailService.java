package com.cpms.community.locker.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Service
public class PickupEmailService {
    private final ObjectProvider<JavaMailSender> mailSenders;
    private final QrCodeService qrCodes;
    private final String from;

    public PickupEmailService(
            ObjectProvider<JavaMailSender> mailSenders,
            QrCodeService qrCodes,
            @Value("${locker.mail.from:}") String from
    ) {
        this.mailSenders = mailSenders;
        this.qrCodes = qrCodes;
        this.from = from;
    }

    public void sendPickupNotice(
            String recipientEmail,
            String pickupCode,
            String lockerLocation,
            String cellNumber,
            Instant expiresAt
    ) {
        JavaMailSender sender = mailSenders.getIfAvailable();
        if (sender == null || from.isBlank()) {
            throw new IllegalStateException(
                    "Pickup email is not configured."
            );
        }

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, true, StandardCharsets.UTF_8.name()
            );

            helper.setFrom(from);
            helper.setTo(recipientEmail);
            helper.setSubject("Your package is ready for pickup");
            helper.setText("""
                    Your package is ready for pickup.

                    Pickup code: %s
                    Locker location: %s
                    Cell: %s
                    Code expires at: %s UTC

                    You can also scan the attached QR code at the locker.
                    """.formatted(
                    pickupCode,
                    lockerLocation,
                    cellNumber,
                    expiresAt
            ));

            helper.addAttachment(
                    "pickup-qr.png",
                    new ByteArrayResource(qrCodes.png(pickupCode)),
                    "image/png"
            );

            sender.send(message);
        } catch (MessagingException exception) {
            throw new IllegalStateException(
                    "Unable to prepare pickup email.",
                    exception
            );
        }
    }
}
