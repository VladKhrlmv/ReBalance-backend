package com.rebalance.controller;

import com.rebalance.dto.request.ThumbnailsRequest;
import com.rebalance.dto.response.ThumbnailResponse;
import com.rebalance.service.image.ImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.List;

@Tag(name = "Receipt images")
@RestController
@RequestMapping(APIVersion.current)
@RequiredArgsConstructor
public class ImageController {
    private final ImageService imageService;

    @Operation(summary = "Upload or replace the receipt image of an expense")
    @PutMapping(value = "/expenses/{expenseId}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> upload(@PathVariable Long expenseId, @RequestParam("file") MultipartFile file) {
        imageService.upload(expenseId, file);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get the receipt image of an expense (JPEG)")
    @GetMapping(value = "/expenses/{expenseId}/image", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> get(@PathVariable Long expenseId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .contentType(MediaType.IMAGE_JPEG)
                .body(imageService.getImage(expenseId));
    }

    @Operation(summary = "Delete the receipt image of an expense")
    @DeleteMapping("/expenses/{expenseId}/image")
    public ResponseEntity<Void> delete(@PathVariable Long expenseId) {
        imageService.delete(expenseId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get thumbnails (base64 JPEG) of several expenses")
    @PostMapping("/images/thumbnails")
    public List<ThumbnailResponse> thumbnails(@RequestBody @Validated ThumbnailsRequest request) {
        return imageService.getThumbnails(request.expenseIds());
    }
}
