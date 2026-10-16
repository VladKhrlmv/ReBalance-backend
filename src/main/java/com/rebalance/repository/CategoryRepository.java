package com.rebalance.repository;

import com.rebalance.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    /** Exact match; the column collation makes it case-insensitive. */
    Optional<Category> findByGroupIdAndName(Long groupId, String name);

    List<Category> findAllByGroupIdOrderByLastUsedAtDesc(Long groupId);
}
