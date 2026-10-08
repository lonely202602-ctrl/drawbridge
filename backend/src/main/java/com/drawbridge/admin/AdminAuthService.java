package com.drawbridge.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.drawbridge.common.BizException;
import com.drawbridge.common.ErrorCode;
import com.drawbridge.configprops.DrawbridgeProperties;
import com.drawbridge.domain.entity.AdminUser;
import com.drawbridge.domain.mapper.AdminUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 管理端登录：BCrypt 校验 + HMAC 签名令牌（12 小时有效，重启不失效）。
 */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private static final long TTL_MS = 12 * 3600_000L;

    private final AdminUserMapper adminUserMapper;
    private final DrawbridgeProperties props;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String login(String username, String password) {
        AdminUser user = adminUserMapper.selectOne(
                Wrappers.<AdminUser>lambdaQuery().eq(AdminUser::getUsername, username));
        if (user == null || !encoder.matches(password, user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        return issue(user.getUsername());
    }

    public String issue(String username) {
        long exp = System.currentTimeMillis() + TTL_MS;
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"u\":\"" + username + "\",\"exp\":" + exp + "}").getBytes(StandardCharsets.UTF_8));
        return payload + "." + sign(payload);
    }

    /** 校验令牌，通过返回用户名。 */
    public String verify(String token) {
        if (token == null || token.indexOf('.') <= 0) {
            return null;
        }
        String[] parts = token.split("\\.", 2);
        String expected = sign(parts[0]);
        if (!java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String json = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        int expIdx = json.indexOf("\"exp\":");
        if (expIdx < 0) {
            return null;
        }
        long exp = Long.parseLong(json.substring(expIdx + 6).replaceAll("[^0-9].*$", ""));
        if (exp < System.currentTimeMillis()) {
            return null;
        }
        int uIdx = json.indexOf("\"u\":\"");
        int uEnd = json.indexOf('"', uIdx + 5);
        return json.substring(uIdx + 5, uEnd);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.adminSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }

    /** 首启种子默认管理员（admin/admin123），已存在则跳过。 */
    @jakarta.annotation.PostConstruct
    void seedAdminAtStartup() {
        seedDefaultAdmin();
    }

    public void seedDefaultAdmin() {
        if (adminUserMapper.selectCount(null) > 0) {
            return;
        }
        AdminUser user = new AdminUser();
        user.setUsername("admin");
        user.setPasswordHash(encoder.encode("admin123"));
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.insert(user);
        org.slf4j.LoggerFactory.getLogger(AdminAuthService.class)
                .warn("[drawbridge] 已创建默认管理员 admin/admin123，请尽快在管理端修改密码");
    }
}
