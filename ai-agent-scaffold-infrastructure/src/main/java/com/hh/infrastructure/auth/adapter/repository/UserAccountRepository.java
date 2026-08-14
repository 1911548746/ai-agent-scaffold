package com.hh.infrastructure.auth.adapter.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hh.application.auth.port.UserAccountPort;
import com.hh.domain.auth.model.UserAccount;
import com.hh.infrastructure.auth.dao.UserAccountDao;
import com.hh.infrastructure.auth.dao.po.UserAccountPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 用户账户 MySQL 适配器。 */
@Service
public class UserAccountRepository implements UserAccountPort {

    private final UserAccountDao userAccountDao;

    public UserAccountRepository(UserAccountDao userAccountDao) {
        this.userAccountDao = userAccountDao;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        UserAccountPO user = userAccountDao.selectOne(new LambdaQueryWrapper<UserAccountPO>()
                .eq(UserAccountPO::getUsername, username)
                .last("LIMIT 1"));
        return Optional.ofNullable(user).map(this::toDomain);
    }

    @Override
    public boolean create(UserAccount userAccount) {
        try {
            return userAccountDao.insert(UserAccountPO.builder()
                    .userId(userAccount.userId())
                    .username(userAccount.username())
                    .passwordHash(userAccount.passwordHash())
                    .createdAt(userAccount.createdAt())
                    .updatedAt(userAccount.updatedAt())
                    .build()) == 1;
        } catch (DuplicateKeyException exception) {
            // 唯一索引才是并发注册的最终裁判；重复用户名属于可预期业务结果。
            return false;
        }
    }

    private UserAccount toDomain(UserAccountPO user) {
        return new UserAccount(user.getUserId(), user.getUsername(), user.getPasswordHash(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
