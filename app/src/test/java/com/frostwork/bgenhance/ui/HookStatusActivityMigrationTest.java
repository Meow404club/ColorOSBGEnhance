package com.frostwork.bgenhance.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class HookStatusActivityMigrationTest {
    @Test
    public void featureAndResidencyPagesUseSharedHookStatusOwnerOnly() throws IOException {
        String feature = source(
                "com/frostwork/bgenhance/ui/FeatureSettingsActivity.java");
        String residency = source(
                "com/frostwork/bgenhance/ui/residency/ResidencyPolicyActivity.java");

        assertFalse(feature.contains("DiagnosticsStore"));
        assertFalse(residency.contains("DiagnosticsStore"));
        assertTrue(feature.contains("HookStatusViewModel.create(this)"));
        assertTrue(residency.contains("HookStatusViewModel.create(this)"));
        assertTrue(feature.contains("hookStatusViewModel.refresh()"));
        assertTrue(residency.contains("hookStatusViewModel.refresh()"));
        assertTrue(feature.contains("hookStatusViewModel.close()"));
        assertTrue(residency.contains("hookStatusViewModel.close()"));
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
