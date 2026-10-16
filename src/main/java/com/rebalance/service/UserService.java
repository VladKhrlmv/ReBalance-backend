package com.rebalance.service;

import com.rebalance.dto.response.UserResponse;
import com.rebalance.entity.Group;
import com.rebalance.entity.User;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import com.rebalance.repository.GroupRepository;
import com.rebalance.security.SignedInUsernameGetter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final GroupRepository groupRepository;
    private final SignedInUsernameGetter signedInUsernameGetter;

    @Transactional(readOnly = true)
    public UserResponse getMyInfo() {
        User user = signedInUsernameGetter.getUser();
        Group personalGroup = groupRepository.findByCreatorIdAndPersonalTrue(user.getId())
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_201));
        return new UserResponse(user.getId(), user.getNickname(), user.getEmail(),
                personalGroup.getCurrency(), personalGroup.getId());
    }
}
