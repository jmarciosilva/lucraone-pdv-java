package br.com.lucraone.pdv.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ApplicationMetadataTest {

    @Test
    void exposesTheApplicationName() {
        assertEquals("LucraOne PDV", ApplicationMetadata.name());
    }
}
