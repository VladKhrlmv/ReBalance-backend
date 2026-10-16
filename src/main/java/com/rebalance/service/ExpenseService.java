package com.rebalance.service;

import com.rebalance.dto.request.ExpenseRequest;
import com.rebalance.dto.request.ExpenseShareRequest;
import com.rebalance.dto.request.PersonalExpenseRequest;
import com.rebalance.dto.request.SettlementRequest;
import com.rebalance.dto.response.GroupExpenseResponse;
import com.rebalance.dto.response.PageResponse;
import com.rebalance.dto.response.PersonalExpenseResponse;
import com.rebalance.entity.*;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import com.rebalance.mapper.DtoMapper;
import com.rebalance.repository.ExpenseImageRepository;
import com.rebalance.repository.ExpenseRepository;
import com.rebalance.repository.GroupMemberRepository;
import com.rebalance.service.money.Money;
import com.rebalance.service.money.SplitCalculator;
import com.rebalance.util.Time;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExpenseService {
    static final int MAX_PAGE_SIZE = 100;
    static final String SETTLEMENT_DESCRIPTION = "Settle up";

    private final ExpenseRepository expenseRepository;
    private final ExpenseImageRepository imageRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessService groupAccessService;
    private final CategoryService categoryService;
    private final NotificationService notificationService;
    private final DtoMapper mapper;

    // ----- group expenses -----

    @Transactional(readOnly = true)
    public PageResponse<GroupExpenseResponse> getGroupExpenses(Long groupId, int page, int size) {
        groupAccessService.requireMember(groupId);
        Page<Expense> expenses = expenseRepository.findAllByGroupIdAndDeletedAtIsNull(groupId, pageRequest(page, size));
        Set<Long> withImage = expenseIdsWithImage(expenses.getContent());
        return PageResponse.of(expenses, e -> mapper.groupExpense(e, withImage.contains(e.getId())));
    }

    @Transactional(readOnly = true)
    public GroupExpenseResponse getGroupExpense(Long groupId, Long expenseId) {
        groupAccessService.requireMember(groupId);
        Expense expense = getLiveExpenseOfGroup(groupId, expenseId);
        return mapper.groupExpense(expense, imageRepository.existsById(expenseId));
    }

    @Transactional
    public GroupExpenseResponse createGroupExpense(Long groupId, ExpenseRequest request) {
        GroupMember me = groupAccessService.requireMember(groupId);
        ExpenseType type = request.type() == null ? ExpenseType.EXPENSE : request.type();

        Expense expense = newExpense(me);
        applyGroupRequest(expense, me.getGroup(), type, request);
        expenseRepository.save(expense);

        if (type == ExpenseType.TRANSFER) {
            notifyGroup(NotificationType.SettlementAdded, me, expense, settlementMessage(me, expense));
        } else {
            notifyGroup(NotificationType.GroupExpenseAdded, me, expense,
                    expenseMessage(me, "added", expense, "in"));
        }
        return mapper.groupExpense(expense, false);
    }

    @Transactional
    public GroupExpenseResponse updateGroupExpense(Long groupId, Long expenseId, ExpenseRequest request) {
        GroupMember me = groupAccessService.requireMember(groupId);
        Expense expense = getLiveExpenseOfGroup(groupId, expenseId);
        checkVersion(expense, request.version());

        ExpenseType type = request.type() == null ? expense.getType() : request.type();
        applyGroupRequest(expense, me.getGroup(), type, request);
        touch(expense, me.getUser());
        expenseRepository.flush(); // bumps the version for the response

        notifyGroup(NotificationType.GroupExpenseEdited, me, expense, expenseMessage(me, "edited", expense, "in"));
        return mapper.groupExpense(expense, imageRepository.existsById(expenseId));
    }

    @Transactional
    public void deleteGroupExpense(Long groupId, Long expenseId, Long version) {
        GroupMember me = groupAccessService.requireMember(groupId);
        Expense expense = getLiveExpenseOfGroup(groupId, expenseId);
        checkVersion(expense, version);

        softDelete(expense, me.getUser());
        notifyGroup(NotificationType.GroupExpenseDeleted, me, expense, expenseMessage(me, "deleted", expense, "from"));
    }

    /**
     * "Mark as paid": records that the debtor paid the creditor, as a TRANSFER expense.
     */
    @Transactional
    public GroupExpenseResponse settle(Long groupId, SettlementRequest request) {
        ExpenseRequest transfer = new ExpenseRequest(
                ExpenseType.TRANSFER,
                SplitType.EQUAL,
                SETTLEMENT_DESCRIPTION,
                request.amount(),
                request.date(),
                null,
                request.debtorMemberId(),
                List.of(new ExpenseShareRequest(request.creditorMemberId(), null, null)),
                null);
        return createGroupExpense(groupId, transfer);
    }

    // ----- personal expenses -----

    @Transactional(readOnly = true)
    public PageResponse<PersonalExpenseResponse> getPersonalExpenses(int page, int size) {
        GroupMember me = groupAccessService.requirePersonalMember();
        Page<Expense> expenses = expenseRepository.findAllByGroupIdAndDeletedAtIsNull(me.getGroup().getId(), pageRequest(page, size));
        Set<Long> withImage = expenseIdsWithImage(expenses.getContent());
        return PageResponse.of(expenses, e -> mapper.personalExpense(e, withImage.contains(e.getId())));
    }

    @Transactional
    public PersonalExpenseResponse createPersonalExpense(PersonalExpenseRequest request) {
        GroupMember me = groupAccessService.requirePersonalMember();
        Expense expense = newExpense(me);
        applyPersonalRequest(expense, me, request);
        expenseRepository.save(expense);
        return mapper.personalExpense(expense, false);
    }

    @Transactional
    public PersonalExpenseResponse updatePersonalExpense(Long expenseId, PersonalExpenseRequest request) {
        GroupMember me = groupAccessService.requirePersonalMember();
        Expense expense = getLiveExpenseOfGroup(me.getGroup().getId(), expenseId);
        checkVersion(expense, request.version());

        applyPersonalRequest(expense, me, request);
        touch(expense, me.getUser());
        expenseRepository.flush();
        return mapper.personalExpense(expense, imageRepository.existsById(expenseId));
    }

    @Transactional
    public void deletePersonalExpense(Long expenseId, Long version) {
        GroupMember me = groupAccessService.requirePersonalMember();
        Expense expense = getLiveExpenseOfGroup(me.getGroup().getId(), expenseId);
        checkVersion(expense, version);
        softDelete(expense, me.getUser());
    }

    // ----- internals -----

    private Expense newExpense(GroupMember me) {
        Expense expense = new Expense();
        expense.setGroup(me.getGroup());
        expense.setCreatedBy(me.getUser());
        expense.setUpdatedBy(me.getUser());
        expense.setCreatedAt(Time.nowUtc());
        expense.setUpdatedAt(Time.nowUtc());
        return expense;
    }

    private void touch(Expense expense, User user) {
        expense.setUpdatedBy(user);
        expense.setUpdatedAt(Time.nowUtc());
    }

    private void softDelete(Expense expense, User user) {
        expense.setDeletedAt(Time.nowUtc());
        expense.setDeletedBy(user);
        touch(expense, user);
    }

    private void applyGroupRequest(Expense expense, Group group, ExpenseType type, ExpenseRequest request) {
        long totalCents = toCents(request.amount());
        List<ExpenseShareRequest> shareRequests = request.shares();

        SplitType splitType = request.splitType();
        if (type == ExpenseType.TRANSFER) {
            if (shareRequests.size() != 1 || shareRequests.get(0).memberId().equals(request.payerMemberId())) {
                throw new RebalanceException(RebalanceErrorType.RB_103,
                        "a transfer goes from the payer to exactly one other member");
            }
            splitType = SplitType.EQUAL;
        }

        Set<Long> shareMemberIds = new HashSet<>();
        for (ExpenseShareRequest share : shareRequests) {
            if (!shareMemberIds.add(share.memberId())) {
                throw new RebalanceException(RebalanceErrorType.RB_105);
            }
        }
        Set<Long> involved = new HashSet<>(shareMemberIds);
        involved.add(request.payerMemberId());

        // lock the involved members so nobody leaves while this expense is saved
        Map<Long, GroupMember> members = memberRepository.lockAllByGroupIdAndIdIn(group.getId(), involved).stream()
                .collect(Collectors.toMap(GroupMember::getId, Function.identity()));
        if (members.size() != involved.size() || members.values().stream().anyMatch(m -> !m.isActive())) {
            throw new RebalanceException(RebalanceErrorType.RB_104);
        }

        List<SplitCalculator.Participant> participants = shareRequests.stream()
                .map(s -> new SplitCalculator.Participant(s.memberId(), s.weight(), s.amount() == null ? null : toCents(s.amount())))
                .toList();
        List<SplitCalculator.Share> shares;
        try {
            shares = SplitCalculator.split(splitType, totalCents, participants);
        } catch (SplitCalculator.InvalidSplitException e) {
            throw new RebalanceException(RebalanceErrorType.RB_103, e.getMessage());
        } catch (ArithmeticException e) {
            throw new RebalanceException(RebalanceErrorType.RB_103, "amounts are too large");
        }

        expense.setType(type);
        expense.setSplitType(splitType);
        expense.setDescription(request.description().trim());
        expense.setAmount(Money.fromCents(totalCents));
        expense.setDate(request.date() == null ? Time.todayUtc() : request.date());
        expense.setCategory(type == ExpenseType.TRANSFER ? null : categoryService.resolve(group, request.category()));
        expense.setPayer(members.get(request.payerMemberId()));
        mergeShares(expense, shares, members);
    }

    private void applyPersonalRequest(Expense expense, GroupMember me, PersonalExpenseRequest request) {
        long totalCents = toCents(request.amount());
        expense.setType(ExpenseType.EXPENSE);
        expense.setSplitType(SplitType.EQUAL);
        expense.setDescription(request.description().trim());
        expense.setAmount(Money.fromCents(totalCents));
        expense.setDate(request.date() == null ? Time.todayUtc() : request.date());
        expense.setCategory(categoryService.resolve(me.getGroup(), request.category()));
        expense.setPayer(me);
        // the owner "owes" the whole personal expense to themselves, so personal balances stay at zero
        mergeShares(expense, List.of(new SplitCalculator.Share(me.getId(), 1, totalCents)), Map.of(me.getId(), me));
    }

    /**
     * Updates shares in place (keyed by member), so the unique (expense, member) constraint is never violated by
     * Hibernate inserting new rows before deleting old ones.
     */
    private void mergeShares(Expense expense, List<SplitCalculator.Share> shares, Map<Long, GroupMember> members) {
        Map<Long, ExpenseShare> existing = expense.getShares().stream()
                .collect(Collectors.toMap(s -> s.getMember().getId(), Function.identity()));
        Set<Long> kept = new HashSet<>();
        for (SplitCalculator.Share share : shares) {
            ExpenseShare entity = existing.get(share.memberId());
            if (entity == null) {
                entity = new ExpenseShare();
                entity.setExpense(expense);
                entity.setMember(members.get(share.memberId()));
                expense.getShares().add(entity);
            }
            entity.setWeight(share.weight());
            entity.setAmount(Money.fromCents(share.amountCents()));
            kept.add(share.memberId());
        }
        expense.getShares().removeIf(s -> !kept.contains(s.getMember().getId()));
    }

    private Expense getLiveExpenseOfGroup(Long groupId, Long expenseId) {
        return expenseRepository.findByIdAndDeletedAtIsNull(expenseId)
                .filter(e -> e.getGroup().getId().equals(groupId))
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_101));
    }

    private static void checkVersion(Expense expense, Long version) {
        if (version == null) {
            throw new RebalanceException(RebalanceErrorType.RB_998, "version is required");
        }
        if (!version.equals(expense.getVersion())) {
            throw new RebalanceException(RebalanceErrorType.RB_102);
        }
    }

    private static long toCents(BigDecimal amount) {
        try {
            return Money.toCents(amount);
        } catch (ArithmeticException e) {
            throw new RebalanceException(RebalanceErrorType.RB_998, "amounts can have at most 2 decimals");
        }
    }

    private static Pageable pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "date").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    private Set<Long> expenseIdsWithImage(List<Expense> expenses) {
        if (expenses.isEmpty()) {
            return Set.of();
        }
        return imageRepository.findAllByExpenseIdIn(expenses.stream().map(Expense::getId).toList()).stream()
                .map(ExpenseImage::getExpenseId)
                .collect(Collectors.toSet());
    }

    private void notifyGroup(NotificationType type, GroupMember actor, Expense expense, String message) {
        notificationService.notify(type, actor.getUser(), actor.getGroup(), expense.getId(), message,
                memberRepository.findActiveUserIds(actor.getGroup().getId()));
    }

    private static String expenseMessage(GroupMember actor, String verb, Expense expense, String preposition) {
        return "%s %s \"%s\" (%s %s) %s %s".formatted(
                actor.getUser().getNickname(), verb, expense.getDescription(),
                expense.getAmount().toPlainString(), actor.getGroup().getCurrency(),
                preposition, actor.getGroup().getName());
    }

    private static String settlementMessage(GroupMember actor, Expense transfer) {
        String receiver = transfer.getShares().get(0).getMember().getUser().getNickname();
        return "%s recorded a payment: %s paid %s %s %s in %s".formatted(
                actor.getUser().getNickname(), transfer.getPayer().getUser().getNickname(), receiver,
                transfer.getAmount().toPlainString(), actor.getGroup().getCurrency(), actor.getGroup().getName());
    }
}
