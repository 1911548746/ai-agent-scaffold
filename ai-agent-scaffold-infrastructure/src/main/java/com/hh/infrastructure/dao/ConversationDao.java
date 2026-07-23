package com.hh.infrastructure.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hh.infrastructure.dao.po.ConversationPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationDao extends BaseMapper<ConversationPO> {

    /**
     * 依赖数据库唯一键完成“创建一次”的仲裁。
     *
     * <p>不能先查询再插入：两个并发请求都可能在查询阶段看不到记录，随后其中一个请求收到
     * 重复键异常。重复键在这里表示同一 conversation 已经由另一个请求创建，因此应视为成功。
     * 使用 upsert 而非 {@code INSERT IGNORE}，避免意外吞掉非重复键的数据错误。</p>
     */
    int upsertByIdentity(ConversationPO conversation);
}
