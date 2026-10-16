package com.rebalance.repository;

import com.rebalance.entity.ExpenseImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ExpenseImageRepository extends JpaRepository<ExpenseImage, Long> {
    List<ExpenseImage> findAllByExpenseIdIn(Collection<Long> expenseIds);
}
