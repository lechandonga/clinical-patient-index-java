package com.github.lechandonga.clinicalpatientindexjava.config;

import com.github.lechandonga.clinicalpatientindexjava.empi.MasterPatientIndex;
import com.github.lechandonga.clinicalpatientindexjava.ingest.FhirJsonMessageParser;
import com.github.lechandonga.clinicalpatientindexjava.ingest.Hl7V2MessageParser;
import com.github.lechandonga.clinicalpatientindexjava.ingest.MessageParser;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewQueue;
import com.github.lechandonga.clinicalpatientindexjava.review.ReviewStagingStore;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean
    public ReviewQueue reviewQueue(
            @Value("${mpi.review.queue-limit:100}") int queueLimit) {
        return new ReviewQueue(queueLimit);
    }

    @Bean
    public ReviewStagingStore reviewStagingStore() {
        return new ReviewStagingStore();
    }
}
