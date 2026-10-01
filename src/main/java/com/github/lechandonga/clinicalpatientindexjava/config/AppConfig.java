package com.github.lechandonga.clinicalpatientindexjava.config;

import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.ingest.FhirJsonMessageParser;
import com.github.lechandonga.clinicalpatientindexjava.ingest.Hl7V2MessageParser;
import com.github.lechandonga.clinicalpatientindexjava.ingest.MessageParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AppConfig {

    @Bean
    public MasterPatientIndex masterPatientIndex() {
        return new MasterPatientIndex();
    }

    @Bean
    public List<MessageParser> messageParsers() {
        return List.of(new Hl7V2MessageParser(), new FhirJsonMessageParser());
    }
}
