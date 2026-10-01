package com.github.lechandonga.clinicalpatientindexjava.security;

import com.github.lechandonga.clinicalpatientindexjava.config.CpiProperties;
import com.github.lechandonga.clinicalpatientindexjava.ingest.ErrorCode;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestException;
import com.github.lechandonga.clinicalpatientindexjava.ingest.IdentifierNormalizer;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 根据请求头 X-Api-Key 解析调用方身份。 */
@Service
public class ApiKeyService {

    public static final String API_KEY_HEADER = "X-Api-Key";

    private final CpiProperties properties;
    private final IdentifierNormalizer identifierNormalizer;

    public ApiKeyService(CpiProperties properties, IdentifierNormalizer identifierNormalizer) {
        this.properties = properties;
        this.identifierNormalizer = identifierNormalizer;
    }

    public ClientPrincipal authenticate(String apiKey) {
        if (!properties.getSecurity().isEnabled()) {
            return new ClientPrincipal("local-disabled-auth",
                    Set.of(Role.values()), Set.of());
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IngestException(ErrorCode.UNAUTHENTICATED,
                    "缺少请求头 " + API_KEY_HEADER);
        }
        CpiProperties.Client client = properties.getSecurity().getClients().get(apiKey);
        if (client == null) {
            throw new IngestException(ErrorCode.UNAUTHENTICATED, "API Key 无效");
        }
        String clientId = "client-" + Integer.toHexString(apiKey.hashCode());
        Set<String> systems = new LinkedHashSet<>();
        for (String system : client.getSystems()) {
            systems.add(identifierNormalizer.normalizeSystem(system));
        }
        return new ClientPrincipal(clientId, Set.copyOf(client.getRoles()), systems);
    }

    public Map<String, CpiProperties.Client> configuredClients() {
        return properties.getSecurity().getClients();
    }
}
