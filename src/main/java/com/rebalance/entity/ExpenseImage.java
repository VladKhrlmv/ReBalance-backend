package com.rebalance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Table(name = "expense_image")
@Entity
public class ExpenseImage {
    @Id
    @Column(name = "expense_id")
    private Long expenseId;

    @Column(name = "storage_key", nullable = false, length = 100)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(nullable = false)
    private Integer width;

    @Column(nullable = false)
    private Integer height;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
