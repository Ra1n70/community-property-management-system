package com.cpms.community.locker;

import com.cpms.community.locker.service.QrCodeService;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class QrCodeServiceTest {

    @Test
    void qrCodeContainsExactlyTheSixDigitPickupCode()
            throws Exception {
        String pickupCode = "003147";
        QrCodeService qrCodes = new QrCodeService();

        BufferedImage image = ImageIO.read(
                new ByteArrayInputStream(qrCodes.png(pickupCode))
        );
        assertThat(image).isNotNull();

        BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(
                        new BufferedImageLuminanceSource(image)
                )
        );

        String scannedCode = new QRCodeReader()
                .decode(bitmap)
                .getText();

        assertThat(scannedCode).isEqualTo(pickupCode);
        assertThat(qrCodes.dataUrl(pickupCode))
                .startsWith("data:image/png;base64,");
    }
}
