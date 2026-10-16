package com.rebalance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One participant's exact share of an expense, in the group currency.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(name = "expense_share")
@Entity
public class ExpenseShare {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private GroupMember member;

    @Column
    private Integer weight;

    @Column(name = "share_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
}
