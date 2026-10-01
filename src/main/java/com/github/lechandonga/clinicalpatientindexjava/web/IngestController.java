package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.ingest.IngestionService;
import com.github.lechandonga.clinicalpatientindexjava.security.ClientPrincipal;
import com.github.lechandonga.clinicalpatientindexjava.security.SecurityInterceptor;
import com.github.lechandonga.clinicalpatientindexjava.web.dto.IngestResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据接入接口。两个路径语义相同，仅用于按格式明确审计；
 * 服务内部仍会自行嗅探格式，错误投递到错误路径也能被正确识别或拒绝。
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestionService ingestionService;

    public IngestController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(path = {"/hl7", "/fhir", ""},
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_JSON_VALUE, "application/hl7-v2"})
    public IngestResponse ingest(@RequestBody String body, HttpServletRequest request) {
        ClientPrincipal principal = (ClientPrincipal) request
                .getAttribute(SecurityInterceptor.PRINCIPAL_ATTRIBUTE);
        return ingestionService.ingest(body, principal);
    }
}
