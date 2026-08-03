package com.frostwork.bgenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Test;

public final class DiagnosticsStorageMigrationTest {
    @Test
    public void retiredPreferencesStoreHasNoSourceOrResourceReferences() throws IOException {
        Path module = moduleDirectory();
        String retiredType = "Diagnostics" + "Store";
        Path retiredSource = module.resolve("src/main/java/com/frostwork/bgenhance")
                .resolve(retiredType + ".java");

        assertFalse("Retired source still exists: " + retiredSource,
                Files.exists(retiredSource));
        assertNoReference(module.resolve("src/main"), retiredType);
        assertNoReference(module.resolve("src/test"), retiredType);
    }

    @Test
    public void formerPreferencesAreReadOnlyForOneTimeSqliteImport() throws IOException {
        Path module = moduleDirectory();
        String preferencesLiteral = "\"" + "hook_" + "diagnostics" + "\"";
        Path migration = module.resolve(
                "src/main/java/com/frostwork/bgenhance/diagnostics/LegacyDiagnosticsMigration.java");
        List<Path> productionReferences = referencesTo(
                module.resolve("src/main"), preferencesLiteral);

        assertEquals(List.of(migration), productionReferences);

        String backend = source(module,
                "com/frostwork/bgenhance/diagnostics/ResidencyDiagnosticsBackend.java");
        String database = source(module,
                "com/frostwork/bgenhance/diagnostics/ResidencyDiagnosticsDb.java");
        assertTrue(backend.contains("return new ResidencyDiagnosticsDb("));
        assertTrue(backend.contains("LegacyDiagnosticsMigration.read(applicationContext)"));
        assertTrue(backend.contains("current.migrateLegacyOnce("));
        assertTrue(database.contains("boolean migrateLegacyOnce(DiagnosticsWriteBatch batch)"));
        assertTrue(database.contains(
                "putMetadataString(db, METADATA_LEGACY_MIGRATION, \"1\")"));
    }

    private static void assertNoReference(Path root, String value) throws IOException {
        List<Path> references = referencesTo(root, value);
        assertTrue("Retired diagnostics store references: " + references,
                references.isEmpty());
    }

    private static List<Path> referencesTo(Path root, String value) throws IOException {
        List<Path> references = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String content = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                if (content.contains(value)) references.add(path);
            }
        }
        return references;
    }

    private static String source(Path module, String relativePath) throws IOException {
        Path path = module.resolve("src/main/java").resolve(relativePath);
        assertTrue("Missing source file: " + path, Files.isRegularFile(path));
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path moduleDirectory() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("src/main"))) {
            return workingDirectory;
        }
        Path app = workingDirectory.resolve("app");
        assertTrue("Missing app module under " + workingDirectory,
                Files.isDirectory(app.resolve("src/main")));
        return app;
    }
}
