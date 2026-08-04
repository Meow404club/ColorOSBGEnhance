package com.frostwork.bgenhance;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class HookResidencyRuntimeSourceContractTest {
    @Test
    public void identitySourceAndReceiverRemainOneRetriedHostTransaction()
            throws IOException {
        String source = source("com/frostwork/bgenhance/HookResidencyRuntime.java");
        int transaction = source.indexOf(
                "HOST_INITIALIZATION.ensure(() -> initializeHost(host))");
        int initializer = source.indexOf("private static void initializeHost", transaction);
        int attachSource = source.indexOf("attachPackageUidSource(host);", initializer);
        int registerReceiver = source.indexOf("registerPackageReceiver(host);", initializer);

        assertTrue(transaction >= 0);
        assertTrue(initializer > transaction);
        assertTrue(attachSource > initializer);
        assertTrue(registerReceiver > attachSource);
        assertTrue(source.contains("Objects.requireNonNull(\n"
                + "                host.getPackageManager(), \"packageManager\")"));
        assertTrue(source.contains("packageManager == sPackageManager"
                + " && sPackageUidSource != null"));
        assertFalse(source.contains("if (packageManager == sPackageManager) return;"));
    }

    private static String source(String relativePath) throws IOException {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path path = workingDirectory.resolve("src/main/java").resolve(relativePath);
        if (!Files.isRegularFile(path)) {
            path = workingDirectory.resolve("app/src/main/java").resolve(relativePath);
        }
        assertTrue("Missing source file: " + path, Files.isRegularFile(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
