package com.drawbridge.adapter;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 适配器注册表：协议族 → 适配器实例。
 */
@Component
public class ProviderRegistry {

    private final Map<ProtocolFamily, ImageProvider> providers = new EnumMap<>(ProtocolFamily.class);

    public ProviderRegistry(List<ImageProvider> list) {
        for (ImageProvider p : list) {
            providers.put(p.family(), p);
        }
    }

    public Optional<ImageProvider> find(String protocol) {
        try {
            return Optional.ofNullable(providers.get(ProtocolFamily.valueOf(protocol)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
