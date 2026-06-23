package com.example.agentdemo.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    @Test
    void returnsSingleChunkForShortText() {
        TextChunker chunker = new TextChunker(20, 5);
        assertThat(chunker.split("short text")).containsExactly("short text");
    }

    @Test
    void splitsLongTextWithOverlap() {
        TextChunker chunker = new TextChunker(10, 3);
        List<String> chunks = chunker.split("abcdefghijklmnopqrst");
        assertThat(chunks).containsExactly("abcdefghij", "hijklmnopq", "opqrst");
    }

    @Test
    void rejectsInvalidOverlap() {
        assertThatThrownBy(() -> new TextChunker(10, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkOverlap must be smaller than chunkSize");
    }
}
