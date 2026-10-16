package com.rebalance.controller;

import com.rebalance.dto.request.PersonalExpenseRequest;
import com.rebalance.dto.response.CategoryResponse;
import com.rebalance.dto.response.PageResponse;
import com.rebalance.dto.response.PersonalExpenseResponse;
import com.rebalance.dto.response.SumByCategoryResponse;
import com.rebalance.service.CategoryService;
import com.rebalance.service.ExpenseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Personal expenses management")
@RestController
@RequestMapping(APIVersion.current + "/personal")
@RequiredArgsConstructor
public class PersonalExpenseController {
    private final ExpenseService expenseService;
    private final CategoryService categoryService;

    @Operation(summary = "Get personal expenses, newest first")
    @GetMapping("/expenses")
    public PageResponse<PersonalExpenseResponse> getExpenses(@RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return expenseService.getPersonalExpenses(page, size);
    }

    @Operation(summary = "Add personal expense")
    @PostMapping("/expenses")
    public ResponseEntity<PersonalExpenseResponse> addExpense(@RequestBody @Validated PersonalExpenseRequest request) {
        return new ResponseEntity<>(expenseService.createPersonalExpense(request), HttpStatus.CREATED);
    }

    @Operation(summary = "Edit personal expense")
    @PutMapping("/expenses/{expenseId}")
    public PersonalExpenseResponse editExpense(@PathVariable Long expenseId,
                                               @RequestBody @Validated PersonalExpenseRequest request) {
        return expenseService.updatePersonalExpense(expenseId, request);
    }

    @Operation(summary = "Delete personal expense")
    @DeleteMapping("/expenses/{expenseId}")
    public ResponseEntity<Void> deleteExpense(@PathVariable Long expenseId, @RequestParam Long version) {
        expenseService.deletePersonalExpense(expenseId, version);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get personal categories, most recently used first")
    @GetMapping("/categories")
    public List<CategoryResponse> getCategories() {
        return categoryService.getPersonalCategories();
    }

    @Operation(summary = "Get personal spending per category")
    @GetMapping("/sum-by-category")
    public List<SumByCategoryResponse> getSumByCategory() {
        return categoryService.getPersonalSumByCategory();
    }
}
