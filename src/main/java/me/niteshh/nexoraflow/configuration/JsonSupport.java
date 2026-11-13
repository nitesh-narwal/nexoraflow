package me.niteshh.nexoraflow.configuration;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Small JSON helper used wherever structured data is stored in JSONB columns or exported. */
@Component
public class JsonSupport {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JsonMapper mapper;

    public JsonSupport(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public static JsonSupport standalone() {
        return new JsonSupport(JsonMapper.builder().build());
    }

    public String toJson(Object value) {
        return mapper.writeValueAsString(value);
    }

    public String toPrettyJson(Object value) {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    public <T> T fromJson(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return mapper.readValue(json, MAP_TYPE);
    }

    public JsonMapper mapper() {
        return mapper;
    }
}
