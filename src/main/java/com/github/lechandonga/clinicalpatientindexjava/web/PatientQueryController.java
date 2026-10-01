package com.github.lechandonga.clinicalpatientindexjava.web;

import com.github.lechandonga.clinicalpatientindexjava.query.PatientQueryService;
import com.github.lechandonga.clinicalpatientindexjava.security.ClientPrincipal;
import com.github.lechandonga.clinicalpatientindexjava.security.SecurityInterceptor;
import com.github.lechandonga.clinicalpatientindexjava.web.dto.DeidentifiedPatientView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patients")
public class PatientQueryController {

    private final PatientQueryService queryService;

    public PatientQueryController(PatientQueryService queryService) {
        this.queryService = queryService;
    }

    /** 按任一来源标识符查询去标识化患者视图。 */
    @GetMapping("/find")
    public DeidentifiedPatientView find(@RequestParam String system,
                                        @RequestParam("identifier") String identifier,
                                        HttpServletRequest request) {
        ClientPrincipal principal = (ClientPrincipal) request
                .getAttribute(SecurityInterceptor.PRINCIPAL_ATTRIBUTE);
        return queryService.query(system, identifier, principal);
    }
}
