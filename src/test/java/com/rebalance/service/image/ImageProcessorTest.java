package com.rebalance.service.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ImageProcessorTest {
    public static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, width / 2, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void scalesLargeImagesDownAndProducesJpegAndThumbnail() throws Exception {
        ImageProcessor.Processed processed = ImageProcessor.process(png(4000, 1000));

        assertThat(processed.width()).isEqualTo(ImageProcessor.MAX_SIDE);
        assertThat(processed.height()).isEqualTo(512);
        BufferedImage full = ImageIO.read(new ByteArrayInputStream(processed.full()));
        BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(processed.thumbnail()));
        assertThat(full.getWidth()).isEqualTo(ImageProcessor.MAX_SIDE);
        assertThat(thumbnail.getWidth()).isEqualTo(ImageProcessor.THUMBNAIL_SIDE);
        assertThat(formatOf(processed.full())).isEqualTo("JPEG");
    }

    @Test
    void neverScalesUp() throws Exception {
        ImageProcessor.Processed processed = ImageProcessor.process(png(100, 50));
        assertThat(processed.width()).isEqualTo(100);
        assertThat(processed.height()).isEqualTo(50);
    }

    @Test
    void outputHasNoExifMetadata() throws Exception {
        ImageProcessor.Processed processed = ImageProcessor.process(png(300, 300));
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(processed.full()))) {
            ImageReader reader = ImageIO.getImageReaders(input).next();
            reader.setInput(input);
            IIOMetadata metadata = reader.getImageMetadata(0);
            // EXIF (APP1) would show up as an "unknown" marker; only the standard JFIF/table markers are written
            org.w3c.dom.Node markers = ((org.w3c.dom.Element) metadata.getAsTree("javax_imageio_jpeg_image_1.0"))
                    .getElementsByTagName("markerSequence").item(0);
            assertThat(((org.w3c.dom.Element) markers).getElementsByTagName("unknown").getLength()).isZero();
            reader.dispose();
        }
    }

    @Test
    void rejectsNonImages() {
        assertThatThrownBy(() -> ImageProcessor.process("definitely not an image".getBytes()))
                .isInstanceOf(ImageProcessor.InvalidImageException.class);
    }

    @Test
    void rejectsHugeDeclaredDimensions() throws Exception {
        // 20000 x 20000 white PNG compresses to a small file but would need 1.6 GB to decode
        assertThatThrownBy(() -> ImageProcessor.process(png(20000, 1)))
                .isInstanceOf(ImageProcessor.InvalidImageException.class);
    }

    private static String formatOf(byte[] data) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            return ImageIO.getImageReaders(input).next().getFormatName().toUpperCase();
        }
    }
}
