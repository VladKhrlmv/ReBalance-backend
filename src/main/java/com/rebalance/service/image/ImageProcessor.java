package com.rebalance.service.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Validates and re-encodes uploaded images. Re-encoding to JPEG drops all metadata (EXIF, GPS position).
 */
public final class ImageProcessor {
    static final int MAX_SIDE = 2048;
    static final int THUMBNAIL_SIDE = 160;
    private static final int MAX_SOURCE_SIDE = 12_000;
    private static final long MAX_SOURCE_PIXELS = 50_000_000L;
    private static final Set<String> ALLOWED_FORMATS = Set.of("jpeg", "jpg", "png", "gif");

    private ImageProcessor() {
    }

    public record Processed(byte[] full, int width, int height, byte[] thumbnail) {
    }

    public static class InvalidImageException extends Exception {
        public InvalidImageException(String message) {
            super(message);
        }
    }

    public static Processed process(byte[] data) throws InvalidImageException, IOException {
        BufferedImage source = decode(data);
        BufferedImage full = scaleToFit(source, MAX_SIDE);
        BufferedImage thumbnail = scaleToFit(source, THUMBNAIL_SIDE);
        return new Processed(toJpeg(full, 0.85f), full.getWidth(), full.getHeight(), toJpeg(thumbnail, 0.8f));
    }

    private static BufferedImage decode(byte[] data) throws InvalidImageException, IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            if (input == null) {
                throw new InvalidImageException("unreadable input");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidImageException("unknown image format");
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!ALLOWED_FORMATS.contains(format)) {
                    throw new InvalidImageException("unsupported format " + format);
                }
                reader.setInput(input, true, true);
                // check the declared size before decoding, so a tiny file can't expand into gigabytes of pixels
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE
                        || (long) width * height > MAX_SOURCE_PIXELS) {
                    throw new InvalidImageException("image dimensions are too large");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidImageException("could not decode image");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new InvalidImageException("could not decode image");
        }
    }

    /** Scales down (never up) to fit in a square of the given side, flattened onto white (JPEG has no alpha). */
    private static BufferedImage scaleToFit(BufferedImage source, int maxSide) {
        double scale = Math.min(1.0, (double) maxSide / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] toJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
