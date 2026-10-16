package com.rebalance.service.image;

import com.rebalance.dto.response.ThumbnailResponse;
import com.rebalance.entity.Expense;
import com.rebalance.entity.ExpenseImage;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import com.rebalance.repository.ExpenseImageRepository;
import com.rebalance.repository.ExpenseRepository;
import com.rebalance.service.GroupAccessService;
import com.rebalance.util.Time;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;

/**
 * Receipt images. Access always goes through the expense: whoever can see the expense can see its image.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {
    static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024;
    private static final String CONTENT_TYPE = "image/jpeg";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExpenseImageRepository imageRepository;
    private final ExpenseRepository expenseRepository;
    private final GroupAccessService groupAccessService;
    private final ImageStorage storage;

    @Transactional
    public void upload(Long expenseId, MultipartFile file) {
        Expense expense = groupAccessService.requireAccessibleExpense(expenseId);
        if (file == null || file.isEmpty()) {
            throw new RebalanceException(RebalanceErrorType.RB_303);
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new RebalanceException(RebalanceErrorType.RB_304);
        }

        ImageProcessor.Processed processed;
        try {
            processed = ImageProcessor.process(file.getBytes());
        } catch (ImageProcessor.InvalidImageException e) {
            throw new RebalanceException(RebalanceErrorType.RB_303);
        } catch (IOException e) {
            log.error("Could not read uploaded image for expense {}", expenseId, e);
            throw new RebalanceException(RebalanceErrorType.RB_302);
        }

        String key = expense.getId() + "-" + HexFormat.of().formatHex(randomBytes());
        try {
            storage.write(fullKey(key), processed.full());
            storage.write(thumbnailKey(key), processed.thumbnail());
        } catch (IOException e) {
            log.error("Could not store image for expense {}", expenseId, e);
            deleteFilesQuietly(key);
            throw new RebalanceException(RebalanceErrorType.RB_302);
        }

        Optional<ExpenseImage> previous = imageRepository.findById(expense.getId());
        String previousKey = previous.map(ExpenseImage::getStorageKey).orElse(null);
        ExpenseImage image = previous.orElseGet(() -> {
            ExpenseImage created = new ExpenseImage();
            created.setExpenseId(expense.getId());
            return created;
        });
        image.setStorageKey(key);
        image.setContentType(CONTENT_TYPE);
        image.setSizeBytes((long) processed.full().length);
        image.setWidth(processed.width());
        image.setHeight(processed.height());
        image.setCreatedAt(Time.nowUtc());
        imageRepository.save(image);

        afterTransaction(previousKey, key);
    }

    @Transactional(readOnly = true)
    public byte[] getImage(Long expenseId) {
        groupAccessService.requireAccessibleExpense(expenseId);
        ExpenseImage image = imageRepository.findById(expenseId)
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_301));
        try {
            byte[] bytes = storage.read(fullKey(image.getStorageKey()));
            if (bytes == null) {
                throw new RebalanceException(RebalanceErrorType.RB_301);
            }
            return bytes;
        } catch (IOException e) {
            log.error("Could not read image of expense {}", expenseId, e);
            throw new RebalanceException(RebalanceErrorType.RB_301);
        }
    }

    @Transactional
    public void delete(Long expenseId) {
        groupAccessService.requireAccessibleExpense(expenseId);
        ExpenseImage image = imageRepository.findById(expenseId)
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_301));
        imageRepository.delete(image);
        afterTransaction(image.getStorageKey(), null);
    }

    /** Thumbnails of the given expenses; ids the user can't access or without an image are left out. */
    @Transactional(readOnly = true)
    public List<ThumbnailResponse> getThumbnails(List<Long> expenseIds) {
        Set<Long> ids = new LinkedHashSet<>(expenseIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Boolean> groupAccess = new HashMap<>();
        Set<Long> accessible = new HashSet<>();
        for (Expense expense : expenseRepository.findLiveWithGroupByIdIn(ids)) {
            Long groupId = expense.getGroup().getId();
            if (groupAccess.computeIfAbsent(groupId, groupAccessService::canAccessGroup)) {
                accessible.add(expense.getId());
            }
        }
        if (accessible.isEmpty()) {
            return List.of();
        }

        List<ThumbnailResponse> thumbnails = new ArrayList<>();
        for (ExpenseImage image : imageRepository.findAllByExpenseIdIn(accessible)) {
            try {
                byte[] bytes = storage.read(thumbnailKey(image.getStorageKey()));
                if (bytes != null) {
                    thumbnails.add(new ThumbnailResponse(image.getExpenseId(), Base64.getEncoder().encodeToString(bytes)));
                }
            } catch (IOException e) {
                log.warn("Could not read thumbnail of expense {}", image.getExpenseId(), e);
            }
        }
        return thumbnails;
    }

    /**
     * Files aren't transactional: delete the replaced files only once the new row is committed, and the new files
     * if the transaction rolls back.
     */
    private void afterTransaction(String keyToDeleteOnCommit, String keyToDeleteOnRollback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED && keyToDeleteOnCommit != null) {
                    deleteFilesQuietly(keyToDeleteOnCommit);
                } else if (status == STATUS_ROLLED_BACK && keyToDeleteOnRollback != null) {
                    deleteFilesQuietly(keyToDeleteOnRollback);
                }
            }
        });
    }

    private void deleteFilesQuietly(String key) {
        try {
            storage.delete(fullKey(key));
            storage.delete(thumbnailKey(key));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Could not delete image files {}", key, e);
        }
    }

    private static String fullKey(String key) {
        return key + ".jpg";
    }

    private static String thumbnailKey(String key) {
        return key + "_thumb.jpg";
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
