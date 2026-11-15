package com.legaldocinsight.document_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;

import static org.assertj.core.api.Assertions.assertThat;

class PublicProfileSafetyTest {
    @Test
    void publicProfileRefusesToStartWhileParserIsolationIsUnavailable() {
        new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(DocumentServiceApplication.class)
            .withPropertyValues(
                "spring.profiles.active=public",
                "spring.datasource.url=jdbc:h2:mem:publicguard;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "storage.local.upload-dir=${java.io.tmpdir}/legaldocinsight-public-guard-test")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Public extraction is disabled until parser isolation is enforced and verified");
            });
    }
}
