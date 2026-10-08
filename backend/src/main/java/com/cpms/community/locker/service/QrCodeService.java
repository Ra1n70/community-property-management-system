package com.cpms.community.locker.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@Service
public class QrCodeService {

    public byte[] png(String pickupCode) {
        if (pickupCode == null || !pickupCode.matches("\\d{6}")) {
            throw new IllegalArgumentException(
                    "Pickup code must contain six digits."
            );
        }

        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    pickupCode,
                    BarcodeFormat.QR_CODE,
                    256,
                    256,
                    Map.of(EncodeHintType.MARGIN, 2)
            );

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return output.toByteArray();
        } catch (WriterException | IOException exception) {
            throw new IllegalStateException(
                    "Unable to generate pickup QR code.",
                    exception
            );
        }
    }

    public String dataUrl(String pickupCode) {
        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(png(pickupCode));
    }
}
