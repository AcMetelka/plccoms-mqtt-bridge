package ocervinka.plcmqttbridge.functions;

import java.util.Map;
import java.util.function.Function;

public class EnumToOptions implements Function<String, String> {
    private final Map<String, String> options;

    public EnumToOptions(Map<String, String> options) {
        this.options = options;
    }

    @Override
    public String apply(String value) {
        if (options == null) {
            return value;
        }
        String mapped = options.get(value);
        return mapped != null ? mapped : value;
    }
}