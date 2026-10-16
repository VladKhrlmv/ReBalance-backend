package com.rebalance.controller;

import com.rebalance.dto.request.ExpenseRequest;
import com.rebalance.dto.request.SettlementRequest;
import com.rebalance.dto.response.CategoryResponse;
import com.rebalance.dto.response.GroupExpenseResponse;
import com.rebalance.dto.response.PageResponse;
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

@Tag(name = "Group expenses management")
@RestController
@RequestMapping(APIVersion.current + "/group/{groupId}")
@RequiredArgsConstructor
public class GroupExpenseController {
    private final ExpenseService expenseService;
    private final CategoryService categoryService;

    @Operation(summary = "Get expenses of group, newest first")
    @GetMapping("/expenses")
    public PageResponse<GroupExpenseResponse> getExpenses(@PathVariable Long groupId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return expenseService.getGroupExpenses(groupId, page, size);
    }

    @Operation(summary = "Get one expense of group")
    @GetMapping("/expenses/{expenseId}")
    public GroupExpenseResponse getExpense(@PathVariable Long groupId, @PathVariable Long expenseId) {
        return expenseService.getGroupExpense(groupId, expenseId);
    }

    @Operation(summary = "Add expense to group")
    @PostMapping("/expenses")
    public ResponseEntity<GroupExpenseResponse> addExpense(@PathVariable Long groupId,
                                                           @RequestBody @Validated ExpenseRequest request) {
        return new ResponseEntity<>(expenseService.createGroupExpense(groupId, request), HttpStatus.CREATED);
    }

    @Operation(summary = "Edit expense (send the version you edited; 409 if someone changed it meanwhile)")
    @PutMapping("/expenses/{expenseId}")
    public GroupExpenseResponse editExpense(@PathVariable Long groupId, @PathVariable Long expenseId,
                                            @RequestBody @Validated ExpenseRequest request) {
        return expenseService.updateGroupExpense(groupId, expenseId, request);
    }

    @Operation(summary = "Delete expense")
    @DeleteMapping("/expenses/{expenseId}")
    public ResponseEntity<Void> deleteExpense(@PathVariable Long groupId, @PathVariable Long expenseId,
                                              @RequestParam Long version) {
        expenseService.deleteGroupExpense(groupId, expenseId, version);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Record a payment between two members (\"mark as paid\")")
    @PostMapping("/settlements")
    public ResponseEntity<GroupExpenseResponse> settle(@PathVariable Long groupId,
                                                       @RequestBody @Validated SettlementRequest request) {
        return new ResponseEntity<>(expenseService.settle(groupId, request), HttpStatus.CREATED);
    }

    @Operation(summary = "Get categories of group, most recently used first")
    @GetMapping("/categories")
    public List<CategoryResponse> getCategories(@PathVariable Long groupId) {
        return categoryService.getGroupCategories(groupId);
    }

    @Operation(summary = "Get spending per category (transfers excluded)")
    @GetMapping("/sum-by-category")
    public List<SumByCategoryResponse> getSumByCategory(@PathVariable Long groupId) {
        return categoryService.getGroupSumByCategory(groupId);
    }
}
