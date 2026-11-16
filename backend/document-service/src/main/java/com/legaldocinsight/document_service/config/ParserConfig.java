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
        if (!cgroupRoot.isBlank() && !executableJar) {
            throw new IllegalStateException("Parser isolation requires a packaged executable jar");
        }
        if (executableJar) command.add("-Dloader.main=" + ParserChildMain.class.getName());
        command.add("-cp");
        String classPath = System.getProperty("java.class.path");
        if (!cgroupRoot.isBlank()) classPath = Path.of(classPath).toAbsolutePath().normalize().toString();
        command.add(classPath);
        command.add(executableJar ? "org.springframework.boot.loader.launch.PropertiesLauncher" : ParserChildMain.class.getName());
        return cgroupRoot.isBlank() ? new ParserProcess(command, Duration.ofMillis(timeoutMillis))
            : new ParserProcess(command, Duration.ofMillis(timeoutMillis), Path.of(cgroupRoot), Path.of(classPath));
    }
}
