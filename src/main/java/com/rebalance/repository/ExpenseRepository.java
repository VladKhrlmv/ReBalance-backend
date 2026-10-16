package com.rebalance.repository;

import com.rebalance.entity.Expense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExpenseRepository extends JpaRepository<Expense, Long> {
    @EntityGraph(attributePaths = {"payer", "payer.user", "category", "createdBy"})
    Page<Expense> findAllByGroupIdAndDeletedAtIsNull(Long groupId, Pageable pageable);

    @EntityGraph(attributePaths = {"group", "payer", "payer.user", "category", "createdBy"})
    Optional<Expense> findByIdAndDeletedAtIsNull(Long id);

    @Query("SELECT e FROM Expense e JOIN FETCH e.group WHERE e.id IN :ids AND e.deletedAt IS NULL")
    List<Expense> findLiveWithGroupByIdIn(Collection<Long> ids);

    /** Total paid per member (payer side of the balance). */
    @Query("SELECT e.payer.id AS memberId, SUM(e.amount) AS total FROM Expense e " +
            "WHERE e.group.id = :groupId AND e.deletedAt IS NULL GROUP BY e.payer.id")
    List<MemberAmount> sumPaidByMember(Long groupId);

    /** Total owed per member (share side of the balance). */
    @Query("SELECT s.member.id AS memberId, SUM(s.amount) AS total FROM ExpenseShare s " +
            "WHERE s.expense.group.id = :groupId AND s.expense.deletedAt IS NULL GROUP BY s.member.id")
    List<MemberAmount> sumSharesByMember(Long groupId);

    /** Total paid by one user, per group. */
    @Query("SELECT e.group.id AS groupId, SUM(e.amount) AS total FROM Expense e " +
            "WHERE e.payer.user.id = :userId AND e.group.id IN :groupIds AND e.deletedAt IS NULL GROUP BY e.group.id")
    List<GroupAmount> sumPaidByUserPerGroup(Long userId, Collection<Long> groupIds);

    /** Total owed by one user, per group. */
    @Query("SELECT s.expense.group.id AS groupId, SUM(s.amount) AS total FROM ExpenseShare s " +
            "WHERE s.member.user.id = :userId AND s.expense.group.id IN :groupIds AND s.expense.deletedAt IS NULL " +
            "GROUP BY s.expense.group.id")
    List<GroupAmount> sumSharesByUserPerGroup(Long userId, Collection<Long> groupIds);

    @Query("SELECT c.name AS category, SUM(e.amount) AS total FROM Expense e LEFT JOIN e.category c " +
            "WHERE e.group.id = :groupId AND e.deletedAt IS NULL AND e.type = com.rebalance.entity.ExpenseType.EXPENSE " +
            "GROUP BY c.name")
    List<CategoryAmount> sumByCategory(Long groupId);

    interface MemberAmount {
        Long getMemberId();

        BigDecimal getTotal();
    }

    interface GroupAmount {
        Long getGroupId();

        BigDecimal getTotal();
    }

    interface CategoryAmount {
        String getCategory();

        BigDecimal getTotal();
    }
}
