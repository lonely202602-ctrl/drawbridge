package com.drawbridge.infrastructure.config;

import com.drawbridge.domain.entity.Channel;
import com.drawbridge.domain.entity.GroupMember;
import com.drawbridge.domain.entity.ModelGroup;
import com.drawbridge.domain.mapper.ChannelMapper;
import com.drawbridge.domain.mapper.GroupMemberMapper;
import com.drawbridge.domain.mapper.ModelGroupMapper;
import com.drawbridge.infrastructure.AesGcmCipher;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道配置快照：渠道/分组/成员全量加载为不可变快照，管理端变更后 refresh() 重建。
 * 转发链路只读快照，不查库；上游 Key 解密在出快照时进行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelConfigService {

    private final ChannelMapper channelMapper;
    private final ModelGroupMapper groupMapper;
    private final GroupMemberMapper memberMapper;
    private final AesGcmCipher cipher;

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of());

    @PostConstruct
    void init() {
        refresh();
    }

    public synchronized void refresh() {
        Map<Long, Channel> channels = new HashMap<>();
        for (Channel c : channelMapper.selectList(null)) {
            channels.put(c.getId(), c);
        }
        Map<String, ModelGroup> groups = new HashMap<>();
        for (ModelGroup g : groupMapper.selectList(null)) {
            groups.put(g.getGroupCode(), g);
        }
        Map<Long, List<GroupMember>> members = new HashMap<>();
        for (GroupMember m : memberMapper.selectList(null)) {
            members.computeIfAbsent(m.getGroupId(), k -> new java.util.ArrayList<>()).add(m);
        }
        this.snapshot = new Snapshot(Map.copyOf(channels), Map.copyOf(groups), deepCopy(members));
        log.info("配置快照已刷新: channels={}, groups={}, memberGroups={}",
                channels.size(), groups.size(), members.size());
    }

    public Snapshot current() {
        return snapshot;
    }

    public ModelGroup groupByCode(String groupCode) {
        return snapshot.groupsByCode().get(groupCode);
    }

    public Channel channel(Long id) {
        return snapshot.channelsById().get(id);
    }

    /** 渠道运行时信息：解密 Key + 合并后的适配参数（路径覆盖/字段指针由适配器与模板默认值合并）。 */
    public ChannelRuntime runtimeOf(Channel channel) {
        String apiKey = channel.getApiKeyEnc() == null ? "" : cipher.decrypt(channel.getApiKeyEnc());
        Map<String, String> paths = channel.getPathOverrides() == null
                ? Map.of() : JsonUtilBridge.toStringMap(channel.getPathOverrides());
        Map<String, Object> extra = channel.getExtraConfig() == null
                ? Map.of() : JsonUtilBridge.toMap(channel.getExtraConfig());
        return new ChannelRuntime(channel.getBaseUrl(), apiKey, paths, extra);
    }

    private Map<Long, List<GroupMember>> deepCopy(Map<Long, List<GroupMember>> src) {
        Map<Long, List<GroupMember>> copy = new HashMap<>();
        src.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Map.copyOf(copy);
    }

    public record Snapshot(
            Map<Long, Channel> channelsById,
            Map<String, ModelGroup> groupsByCode,
            Map<Long, List<GroupMember>> membersByGroupId) {
    }

    /** 渠道运行时参数（baseUrl 原样，含或不含 /v1 由适配器模板约定）。 */
    public record ChannelRuntime(
            String baseUrl,
            String apiKey,
            Map<String, String> pathOverrides,
            Map<String, Object> extra) {
    }

    /** 避免配置服务直接依赖 Jackson 细节的小桥。 */
    static final class JsonUtilBridge {
        static Map<String, String> toStringMap(String json) {
            Map<String, String> result = new HashMap<>();
            com.fasterxml.jackson.databind.JsonNode node = com.drawbridge.common.JsonUtil.readTree(json);
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) {
                    result.put(e.getKey(), e.getValue().asText());
                }
            });
            return Map.copyOf(result);
        }

        static Map<String, Object> toMap(String json) {
            return com.drawbridge.common.JsonUtil.MAPPER.convertValue(
                    com.drawbridge.common.JsonUtil.readTree(json),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        }
    }
}
