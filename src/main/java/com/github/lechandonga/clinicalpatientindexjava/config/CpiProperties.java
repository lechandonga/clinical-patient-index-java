package com.github.lechandonga.clinicalpatientindexjava.config;

import com.github.lechandonga.clinicalpatientindexjava.security.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本地 EMPI 服务的全部可配置项，前缀为 {@code cpi}。
 */
@ConfigurationProperties(prefix = "cpi")
public class CpiProperties {

    private final Security security = new Security();
    private final Identifier identifier = new Identifier();
    private final Deidentification deidentification = new Deidentification();
    private final Audit audit = new Audit();

    public Security getSecurity() {
        return security;
    }

    public Identifier getIdentifier() {
        return identifier;
    }

    public Deidentification getDeidentification() {
        return deidentification;
    }

    public Audit getAudit() {
        return audit;
    }

    public static class Security {
        /** 关闭后所有接口不再校验 API Key，仅供本地离线调试。 */
        private boolean enabled = true;
        private Map<String, Client> clients = new HashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<String, Client> getClients() {
            return clients;
        }

        public void setClients(Map<String, Client> clients) {
            this.clients = clients;
        }
    }

    public static class Client {
        private Set<Role> roles = new LinkedHashSet<>();
        /** 允许提交/查询的标识符系统（assigning authority）白名单，为空表示不限制。 */
        private Set<String> systems = new LinkedHashSet<>();

        public Set<Role> getRoles() {
            return roles;
        }

        public void setRoles(Set<Role> roles) {
            this.roles = roles;
        }

        public Set<String> getSystems() {
            return systems;
        }

        public void setSystems(Set<String> systems) {
            this.systems = systems;
        }
    }

    public static class Identifier {
        /** 来源系统别名 -> 规范化系统名，例如 EMR -> HIS，大小写不敏感。 */
        private Map<String, String> systemAliases = new HashMap<>();
        /** 标识符值上需要剥离的前缀，按配置顺序匹配，大小写不敏感。 */
        private List<String> stripPrefixes = new ArrayList<>();
        /** 标识符值上需要剥离的后缀。 */
        private List<String> stripSuffixes = new ArrayList<>();
        /** 规范化后标识符允许的最大长度。 */
        private int maxLength = 64;

        public Map<String, String> getSystemAliases() {
            return systemAliases;
        }

        public void setSystemAliases(Map<String, String> systemAliases) {
            this.systemAliases = systemAliases;
        }

        public List<String> getStripPrefixes() {
            return stripPrefixes;
        }

        public void setStripPrefixes(List<String> stripPrefixes) {
            this.stripPrefixes = stripPrefixes;
        }

        public List<String> getStripSuffixes() {
            return stripSuffixes;
        }

        public void setStripSuffixes(List<String> stripSuffixes) {
            this.stripSuffixes = stripSuffixes;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(int maxLength) {
            this.maxLength = maxLength;
        }
    }

    public static class Deidentification {
        /** HMAC-SHA256 伪名派生密钥，生产环境必须通过环境变量覆盖。 */
        private String secret = "local-development-secret-change-me";

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    public static class Audit {
        /** 内存审计日志保留的最大条数，超出后丢弃最旧记录。 */
        private int inMemoryCapacity = 10_000;

        public int getInMemoryCapacity() {
            return inMemoryCapacity;
        }

        public void setInMemoryCapacity(int inMemoryCapacity) {
            this.inMemoryCapacity = inMemoryCapacity;
        }
    }
}
