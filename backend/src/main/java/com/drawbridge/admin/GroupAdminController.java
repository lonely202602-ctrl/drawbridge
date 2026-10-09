package com.drawbridge.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.common.ApiResponse;
import com.drawbridge.common.BizException;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.domain.entity.ApiToken;
import com.drawbridge.domain.mapper.ApiTokenMapper;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.mapper.GroupMemberMapper;
import com.drawbridge.domain.mapper.ModelGroupMapper;
import com.drawbridge.infrastructure.config.ChannelConfigService;
import com.drawbridge.route.RouteEngine;
import com.drawbridge.route.RouteStrategy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端-分组：业务方 model 名 + 成员条目 + 路由策略 + 我方售价。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/groups")
public class GroupAdminController {

    private final ModelGroupMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final ChannelMapper channelMapper;
    private final ApiTokenMapper tokenMapper;
    private final ChannelConfigService configService;
    private final RouteEngine routeEngine;

    @Data
    public static class GroupUpsertRequest {
        @NotBlank(message = "分组编码不能为空")
        private String groupCode;
        private String displayName;
        @NotNull(message = "路由策略不能为空")
        private RouteStrategy strategy;
        private String remark;
    }

    @Data
    public static class MemberUpsertRequest {
        @NotNull(message = "渠道不能为空")
        private Long channelId;
        @NotBlank(message = "上游模型名不能为空")
        private String upstreamModel;
        private Integer weight = 1;
        private Integer sortOrder = 0;
        private Integer enabled = 1;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        Map<Long, Long> memberCounts = memberMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(GroupMember::getGroupId, Collectors.counting()));
        List<Map<String, Object>> rows = groupMapper.selectList(
                        Wrappers.<ModelGroup>lambdaQuery().orderByDesc(ModelGroup::getId))
                .stream()
                .map(g -> Map.<String, Object>of(
                        "id", g.getId(),
                        "groupCode", g.getGroupCode(),
                        "displayName", nz(g.getDisplayName()),
                        "strategy", g.getStrategy(),
                        "enabled", g.getEnabled(),
                        "memberCount", memberCounts.getOrDefault(g.getId(), 0L),
                        "remark", nz(g.getRemark())))
                .toList();
        return ApiResponse.ok(rows);
    }

    @PostMapping
    public ApiResponse<Long> create(@Validated @RequestBody GroupUpsertRequest body) {
        if (groupMapper.selectCount(Wrappers.<ModelGroup>lambdaQuery()
                .eq(ModelGroup::getGroupCode, body.getGroupCode())) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分组编码已存在: " + body.getGroupCode());
        }
        ModelGroup group = new ModelGroup();
        copy(body, group);
        LocalDateTime now = LocalDateTime.now();
        group.setEnabled(1);
        group.setCreatedAt(now);
        group.setUpdatedAt(now);
        groupMapper.insert(group);
        configService.refresh();
        routeEngine.resetPolling(group.getId());
        return ApiResponse.ok(group.getId());
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Validated @RequestBody GroupUpsertRequest body) {
        ModelGroup group = groupMapper.selectById(id);
        if (group == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分组不存在");
        }
        String newCode = body.getGroupCode().trim();
        String oldCode = group.getGroupCode();
        if (!newCode.equalsIgnoreCase(oldCode)
                && groupMapper.selectCount(Wrappers.<ModelGroup>lambdaQuery()
                        .eq(ModelGroup::getGroupCode, newCode)
                        .ne(ModelGroup::getId, id)) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分组编码已存在: " + newCode);
        }
        copy(body, group);
        group.setGroupCode(newCode);
        group.setUpdatedAt(LocalDateTime.now());
        groupMapper.updateById(group);
        if (!newCode.equals(oldCode)) {
            cascadeTokenAllowedGroups(oldCode, newCode);
        }
        configService.refresh();
        routeEngine.resetPolling(id);
        return ApiResponse.ok();
    }

    /** 分组改名后同步令牌 allowed_groups 里的旧编码，否则限了分组的令牌会丢失该模型权限。 */
    private void cascadeTokenAllowedGroups(String oldCode, String newCode) {
        for (ApiToken token : tokenMapper.selectList(Wrappers.<ApiToken>lambdaQuery()
                .isNotNull(ApiToken::getAllowedGroups))) {
            String allowed = token.getAllowedGroups();
            if (allowed == null || allowed.isBlank()) {
                continue;
            }
            List<String> codes = com.drawbridge.common.JsonUtil.readTree(allowed).valueStream()
                    .filter(com.fasterxml.jackson.databind.JsonNode::isTextual)
                    .map(com.fasterxml.jackson.databind.JsonNode::asText)
                    .toList();
            if (!codes.contains(oldCode)) {
                continue;
            }
            List<String> replaced = codes.stream().map(c -> c.equals(oldCode) ? newCode : c).toList();
            token.setAllowedGroups(com.drawbridge.common.JsonUtil.toJson(replaced));
            token.setUpdatedAt(LocalDateTime.now());
            tokenMapper.updateById(token);
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ModelGroup group = groupMapper.selectById(id);
        if (group == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分组不存在");
        }
        group.setEnabled(0);
        group.setUpdatedAt(LocalDateTime.now());
        groupMapper.updateById(group);
        configService.refresh();
        return ApiResponse.ok();
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        ModelGroup group = groupMapper.selectById(id);
        if (group == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分组不存在");
        }
        Map<Long, Channel> channels = channelMapper.selectList(null).stream()
                .collect(Collectors.toMap(Channel::getId, Function.identity()));
        List<Map<String, Object>> members = memberMapper.selectList(Wrappers.<GroupMember>lambdaQuery()
                        .eq(GroupMember::getGroupId, id)
                        .orderByAsc(GroupMember::getSortOrder))
                .stream()
                .map(m -> {
                    Channel c = channels.get(m.getChannelId());
                    return Map.<String, Object>of(
                            "id", m.getId(),
                            "channelId", m.getChannelId(),
                            "channelName", c == null ? "(已删除)" : c.getName(),
                            "channelProtocol", c == null ? "" : c.getProtocol(),
                            "channelStatus", c == null ? -1 : c.getStatus(),
                            "upstreamModel", m.getUpstreamModel(),
                            "weight", m.getWeight(),
                            "sortOrder", m.getSortOrder(),
                            "enabled", m.getEnabled());
                })
                .toList();
        return ApiResponse.ok(Map.of(
                "id", group.getId(),
                "groupCode", group.getGroupCode(),
                "displayName", nz(group.getDisplayName()),
                "strategy", group.getStrategy(),
                "enabled", group.getEnabled(),
                "remark", nz(group.getRemark()),
                "members", members));
    }

    @PostMapping("/{id}/members")
    public ApiResponse<Map<String, Object>> addMember(@PathVariable Long id, @Validated @RequestBody MemberUpsertRequest body) {
        requireGroup(id);
        Channel channel = channelMapper.selectById(body.getChannelId());
        if (channel == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "渠道不存在");
        }
        Long dup = memberMapper.selectCount(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getGroupId, id)
                .eq(GroupMember::getChannelId, body.getChannelId())
                .eq(GroupMember::getUpstreamModel, body.getUpstreamModel()));
        if (dup > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该渠道的「" + body.getUpstreamModel() + "」已在本分组中，请勿重复添加（若之前停用了，直接点成员上的“启”即可）");
        }
        GroupMember member = new GroupMember();
        member.setGroupId(id);
        member.setChannelId(body.getChannelId());
        member.setUpstreamModel(body.getUpstreamModel());
        member.setWeight(body.getWeight() == null ? 1 : body.getWeight());
        member.setSortOrder(body.getSortOrder() == null ? 0 : body.getSortOrder());
        member.setEnabled(body.getEnabled() == null ? 1 : body.getEnabled());
        LocalDateTime now = LocalDateTime.now();
        member.setCreatedAt(now);
        member.setUpdatedAt(now);
        memberMapper.insert(member);
        configService.refresh();
        routeEngine.resetPolling(id);
        Map<String, Object> created = new java.util.HashMap<>();
        created.put("id", member.getId());
        created.put("channelId", member.getChannelId());
        created.put("channelName", channel.getName());
        created.put("upstreamModel", member.getUpstreamModel());
        created.put("weight", member.getWeight());
        created.put("sortOrder", member.getSortOrder());
        created.put("enabled", member.getEnabled());
        return ApiResponse.ok(created);
    }

    /** 路径参数类型不匹配（如 /members/undefined）：400，不进 500。 */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ApiResponse.fail(ErrorCode.PARAM_INVALID, "参数类型不合法: " + e.getName());
    }

    @PutMapping("/{id}/members/{memberId}")
    public ApiResponse<Void> updateMember(@PathVariable Long id, @PathVariable Long memberId,
                                          @RequestBody MemberUpsertRequest body) {
        GroupMember member = memberMapper.selectById(memberId);
        if (member == null || !member.getGroupId().equals(id)) {
            throw new BizException(ErrorCode.NOT_FOUND, "成员不存在");
        }
        if (body.getUpstreamModel() != null) {
            member.setUpstreamModel(body.getUpstreamModel());
        }
        if (body.getWeight() != null) {
            member.setWeight(body.getWeight());
        }
        if (body.getSortOrder() != null) {
            member.setSortOrder(body.getSortOrder());
        }
        if (body.getEnabled() != null) {
            member.setEnabled(body.getEnabled());
        }
        member.setUpdatedAt(LocalDateTime.now());
        memberMapper.updateById(member);
        configService.refresh();
        routeEngine.resetPolling(id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ApiResponse<Void> deleteMember(@PathVariable Long id, @PathVariable Long memberId) {
        memberMapper.delete(Wrappers.<GroupMember>lambdaQuery()
                .eq(GroupMember::getId, memberId)
                .eq(GroupMember::getGroupId, id));
        configService.refresh();
        routeEngine.resetPolling(id);
        return ApiResponse.ok();
    }

    private void requireGroup(Long id) {
        if (groupMapper.selectById(id) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "分组不存在");
        }
    }

    private void copy(GroupUpsertRequest body, ModelGroup group) {
        group.setGroupCode(body.getGroupCode());
        group.setDisplayName(body.getDisplayName());
        group.setStrategy(body.getStrategy().name());
        group.setRemark(body.getRemark());
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }
}
