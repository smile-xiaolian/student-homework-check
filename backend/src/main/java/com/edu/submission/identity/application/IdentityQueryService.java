package com.edu.submission.identity.application;

import com.edu.submission.identity.adapter.out.UserAccountMapper;
import com.edu.submission.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IdentityQueryService {

    private final UserAccountMapper userAccountMapper;

    public IdentityQueryService(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    public Optional<UserAccount> findById(UUID id) {
        return Optional.ofNullable(userAccountMapper.selectById(id));
    }

    public Optional<UserAccount> findByLogin(String login) {
        return Optional.ofNullable(userAccountMapper.findByLogin(login));
    }
}

