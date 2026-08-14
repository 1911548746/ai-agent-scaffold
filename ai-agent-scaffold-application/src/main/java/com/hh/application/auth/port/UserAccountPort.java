package com.hh.application.auth.port;

import com.hh.domain.auth.model.UserAccount;

import java.util.Optional;

/** 用户账户的持久化端口。 */
public interface UserAccountPort {

    Optional<UserAccount> findByUsername(String username);

    /**
     * 由数据库唯一索引仲裁用户名并发注册。
     *
     * @return 成功创建返回 true；用户名已存在返回 false。
     */
    boolean create(UserAccount userAccount);
}
