package com.iminiapp.backend.verification.application;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrCodeServiceTest {
    @Test
    void generatedDataUrlCanBeScannedBackToOriginalTicketCode() throws Exception {
        String code = "BFHV-123-1-ABCDEF123456";
        String dataUrl = new QrCodeService().asDataUrl(code);
        assertTrue(dataUrl.startsWith("data:image/png;base64,"));
        byte[] png = Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(ImageIO.read(new ByteArrayInputStream(png)))));
        assertEquals(code, new MultiFormatReader().decode(bitmap).getText());
    }
}
