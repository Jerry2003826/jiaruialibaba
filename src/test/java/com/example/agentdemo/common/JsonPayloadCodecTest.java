package com.example.agentdemo.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonPayloadCodecTest {

    private final JsonPayloadCodec codec = new JsonPayloadCodec(new ObjectMapper());

    @Test
    void writesAndReadsPayloadWithBusinessErrorMetadata() {
        SamplePayload payload = new SamplePayload("demo", 3);

        String json = codec.write(payload, "PAYLOAD_WRITE_FAILED", "Failed to write payload");
        SamplePayload restored = codec.read(json, SamplePayload.class, "PAYLOAD_READ_FAILED",
                "Failed to read payload");

        assertThat(restored).isEqualTo(payload);
    }

    @Test
    void readWrapsInvalidJsonAsBusinessException() {
        assertThatThrownBy(() -> codec.read("{bad json}", SamplePayload.class, "PAYLOAD_READ_FAILED",
                "Failed to read payload"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAYLOAD_READ_FAILED"));
    }

    @Test
    void writeWrapsSerializationFailureAsBusinessException() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("self", payload);

        assertThatThrownBy(() -> codec.write(payload, "PAYLOAD_WRITE_FAILED", "Failed to write payload"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PAYLOAD_WRITE_FAILED"));
    }

    private record SamplePayload(String name, int count) {
    }

}
