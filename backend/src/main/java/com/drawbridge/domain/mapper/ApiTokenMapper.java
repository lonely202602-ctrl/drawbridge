package com.drawbridge.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.drawbridge.domain.entity.ApiToken;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface ApiTokenMapper extends BaseMapper<ApiToken> {

    /** 原子冻结：额度足够才累加，返回受影响行数（0=额度不足/令牌不可用）。quota_total 为 NULL 视为不限额。 */
    @Update("UPDATE api_token SET quota_used = quota_used + #{amount}, updated_at = #{now} "
            + "WHERE id = #{tokenId} AND status = 1 "
            + "AND (quota_total IS NULL OR quota_used + #{amount} <= quota_total)")
    int freeze(@Param("tokenId") Long tokenId, @Param("amount") BigDecimal amount, @Param("now") Object now);

    /** 原子退款（失败解冻），不为负。 */
    @Update("UPDATE api_token SET quota_used = GREATEST(quota_used - #{amount}, 0), updated_at = #{now} "
            + "WHERE id = #{tokenId}")
    int refund(@Param("tokenId") Long tokenId, @Param("amount") BigDecimal amount, @Param("now") Object now);
}
