package com.web.backen.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolve legacy relative paths against the backend directory, regardless of launcher cwd. */
@Component
public class RuntimePaths {
    private final Path backend;
    public RuntimePaths(@Value("${WEB_PROJECT_ROOT:}") String projectRoot) {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        backend = projectRoot == null || projectRoot.isBlank()
                ? (Files.isDirectory(cwd.resolve("backen/src")) || Files.isRegularFile(cwd.resolve("backen/pom.xml"))
                    ? cwd.resolve("backen") : cwd)
                : Path.of(projectRoot).toAbsolutePath().normalize().resolve("backen");
    }
    public Path resolve(String path) {
        Path value = Path.of(path);
        return (value.isAbsolute() ? value : backend.resolve(value)).normalize();
    }
    public Path runtimeRoot() { return resolve("../.run"); }
    public Path deploymentLock() {
        String configured = System.getenv("DEPLOYMENT_LOCK_PATH");
        return configured == null || configured.isBlank() ? runtimeRoot().resolve("deployment.lock") : resolve(configured);
    }
}
