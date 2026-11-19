package fuchsia;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FilenameValidationTest {

    // ---------- allowed ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "report.pdf",
            "my file.txt",
            "report.v2.final.pdf",
            "résumé.pdf",
            "a",
            "UPPER.TXT",
            "no-extension"
    })
    void acceptsNormalFilenames(String name) {
        assertNull(FileIngestionServiceImpl.validateFilename(name));
    }

    @Test
    void accepts255CharacterName() {
        assertNull(FileIngestionServiceImpl.validateFilename("a".repeat(255)));
    }

    // ---------- rejected ----------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void rejectsMissingFilenames(String name) {
        assertEquals("Filename is required", FileIngestionServiceImpl.validateFilename(name));
    }

    @Test
    void rejects256CharacterName() {
        String problem = FileIngestionServiceImpl.validateFilename("a".repeat(256));
        assertNotNull(problem);
        assertTrue(problem.contains("too long"), problem);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a/b.txt",
            "a\\b.txt",
            "../evil.txt",
            "..\\evil.txt",
            "/etc/passwd",
            "folder/"
    })
    void rejectsPathSeparators(String name) {
        assertEquals("Filename must not contain path separators",
                FileIngestionServiceImpl.validateFilename(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {".secret", ".", "..", ".DS_Store"})
    void rejectsLeadingDot(String name) {
        assertEquals("Filenames starting with '.' are not allowed",
                FileIngestionServiceImpl.validateFilename(name));
    }
}