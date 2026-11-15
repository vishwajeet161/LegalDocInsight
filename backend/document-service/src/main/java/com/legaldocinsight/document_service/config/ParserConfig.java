package com.legaldocinsight.document_service.config;

import com.legaldocinsight.document_service.service.impl.ParserChildMain;
import com.legaldocinsight.document_service.service.impl.ParserProcess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;

@Configuration
public class ParserConfig {
    @Bean
    ParserProcess parserProcess(@Value("${document.extraction.parser-timeout-ms:30000}") long timeoutMillis,
                                @Value("${document.extraction.cgroup-root:}") String cgroupRoot) {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx256m");
        boolean executableJar = ParserChildMain.class.getClassLoader().getClass().getName()
            .startsWith("org.springframework.boot.loader.launch.");
        if (executableJar) command.add("-Dloader.main=" + ParserChildMain.class.getName());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(executableJar ? "org.springframework.boot.loader.launch.PropertiesLauncher" : ParserChildMain.class.getName());
        return cgroupRoot.isBlank() ? new ParserProcess(command, Duration.ofMillis(timeoutMillis))
            : new ParserProcess(command, Duration.ofMillis(timeoutMillis), Path.of(cgroupRoot));
    }
}
