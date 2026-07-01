package com.example.agentdemo.common;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PublicIdGeneratorTest {

    private final PublicIdGenerator generator = new PublicIdGenerator();

    @Test
    void nextUsesExpectedPrefixFormat() {
        String publicId = generator.next("kb");

        assertThat(publicId).startsWith("kb-");
        assertThat(publicId).hasSize(23);
        assertThat(publicId.substring(3)).matches("[a-f0-9]{20}");
    }

    @Test
    void nextGeneratesDistinctIds() {
        assertThat(generator.next("app")).isNotEqualTo(generator.next("app"));
    }

    @Test
    void nextUuidReturnsParsableUuidString() {
        String uuid = generator.nextUuid();

        assertThat(UUID.fromString(uuid).toString()).isEqualTo(uuid);
    }

}
