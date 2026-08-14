package com.hh.infrastructure.auth.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.auth.dao.po.UserAccountPO;
import org.apache.ibatis.annotations.Mapper;

/** 用户账户 DAO。用户名唯一约束由数据库承担并发仲裁。 */
@Mapper
public interface UserAccountDao extends BaseMapper<UserAccountPO> {
}
