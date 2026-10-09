package com.drawbridge.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.drawbridge.domain.entity.ApiToken;

/**
 * 令牌 Mapper。drawbridge 是纯网关不做计费——额度相关 SQL 已随计价功能一并移除。
 */
public interface ApiTokenMapper extends BaseMapper<ApiToken> {
}
