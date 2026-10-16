package com.rebalance.service;

import com.rebalance.dto.response.CategoryResponse;
import com.rebalance.dto.response.SumByCategoryResponse;
import com.rebalance.entity.Category;
import com.rebalance.entity.Group;
import com.rebalance.mapper.DtoMapper;
import com.rebalance.repository.CategoryRepository;
import com.rebalance.repository.ExpenseRepository;
import com.rebalance.util.Time;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CategoryService {
    static final String UNCATEGORIZED = "Uncategorized";

    private final CategoryRepository categoryRepository;
    private final ExpenseRepository expenseRepository;
    private final GroupAccessService groupAccessService;
    private final DtoMapper mapper;

    /**
     * Finds the group's category by exact (case-insensitive) name or creates it. Blank names mean "no category".
     */
    @Transactional
    public Category resolve(Group group, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = rawName.trim();
        Category category = categoryRepository.findByGroupIdAndName(group.getId(), name)
                .orElseGet(() -> {
                    Category created = new Category();
                    created.setGroup(group);
                    created.setName(name);
                    created.setLastUsedAt(Time.nowUtc());
                    return categoryRepository.save(created);
                });
        category.setLastUsedAt(Time.nowUtc());
        return category;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getGroupCategories(Long groupId) {
        groupAccessService.requireMember(groupId);
        return categories(groupId);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> getPersonalCategories() {
        return categories(groupAccessService.requirePersonalMember().getGroup().getId());
    }

    @Transactional(readOnly = true)
    public List<SumByCategoryResponse> getGroupSumByCategory(Long groupId) {
        groupAccessService.requireMember(groupId);
        return sums(groupId);
    }

    @Transactional(readOnly = true)
    public List<SumByCategoryResponse> getPersonalSumByCategory() {
        return sums(groupAccessService.requirePersonalMember().getGroup().getId());
    }

    private List<CategoryResponse> categories(Long groupId) {
        return categoryRepository.findAllByGroupIdOrderByLastUsedAtDesc(groupId).stream()
                .map(mapper::category)
                .toList();
    }

    private List<SumByCategoryResponse> sums(Long groupId) {
        return expenseRepository.sumByCategory(groupId).stream()
                .map(row -> mapper.sumByCategory(row.getCategory() == null ? UNCATEGORIZED : row.getCategory(), row.getTotal()))
                .sorted(Comparator.comparing(SumByCategoryResponse::sum).reversed())
                .toList();
    }
}
