package com.cpms.community.locker;

import com.cpms.community.locker.service.PickupEmailService;
import com.cpms.community.locker.service.QrCodeService;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import javax.imageio.ImageIO;
import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PickupEmailServiceTest {

    @Test
    void emailContainsCodeLocationExpiryAndQrAttachment()
            throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> providers =
                mock(ObjectProvider.class);

        MimeMessage message = new MimeMessage(
                Session.getInstance(new Properties())
        );
        when(providers.getIfAvailable()).thenReturn(sender);
        when(sender.createMimeMessage()).thenReturn(message);

        Instant expiry = Instant.parse("2026-10-01T12:00:00Z");
        PickupEmailService service = new PickupEmailService(
                providers, new QrCodeService(), "locker@test.local"
        );

        service.sendPickupNotice(
                "resident@test.local",
                "003147",
                "Main Lobby",
                "A01",
                expiry
        );

        verify(sender).send(message);
        message.saveChanges();

        Multipart outer = (Multipart) message.getContent();
        Object firstPart = outer.getBodyPart(0).getContent();
        String body = firstPart instanceof Multipart nested
                ? nested.getBodyPart(0).getContent().toString()
                : firstPart.toString();

        assertThat(body).contains(
                "003147", "Main Lobby", "A01",
                "2026-10-01T12:00:00Z"
        );

        var attachment = outer.getBodyPart(
                outer.getCount() - 1
        );
        assertThat(attachment.getFileName())
                .isEqualTo("pickup-qr.png");
        assertThat(ImageIO.read(attachment.getInputStream()))
                .isNotNull();
    }
}
