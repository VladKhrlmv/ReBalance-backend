package com.rebalance.service.image;

import com.rebalance.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.regex.Pattern;

/**
 * Stores images as files in one directory (a Docker volume in production).
 */
@Slf4j
@Component
public class FileSystemImageStorage implements ImageStorage {
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9]+)?$");

    private final Path root;

    public FileSystemImageStorage(AppProperties properties) throws IOException {
        this.root = Paths.get(properties.storage().path()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        log.info("Storing images in {}", root);
    }

    @Override
    public void write(String key, byte[] data) throws IOException {
        Path target = resolve(key);
        Path temp = Files.createTempFile(root, ".upload-", ".tmp");
        try {
            Files.write(temp, data);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public byte[] read(String key) throws IOException {
        Path path = resolve(key);
        return Files.exists(path) ? Files.readAllBytes(path) : null;
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        if (key == null || !SAFE_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.getParent().equals(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return path;
    }
}
