package br.com.lucraone.pdv.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApplicationMetadataTest {

    @Test
    void exposesTheApplicationName() {
        assertEquals("LucraOne PDV", ApplicationMetadata.name());
    }

    @Test
    void exposesTheBuildVersion() {
        assertTrue(ApplicationMetadata.version().matches("\\d+\\.\\d+\\.\\d+.*"), ApplicationMetadata.version());
    }
}
