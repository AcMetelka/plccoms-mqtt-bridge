package ocervinka.plcmqttbridge.functions;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class OptionsToEnum implements Function<String, String> {
    private final Map<String, String> reverseOptions;

    public OptionsToEnum(Map<String, String> options) {
        this.reverseOptions = new HashMap<>();
        if (options != null) {
            for (Map.Entry<String, String> e : options.entrySet()) {
                this.reverseOptions.put(e.getValue(), e.getKey());
            }
        }
    }

    @Override
    public String apply(String value) {
        String mapped = reverseOptions.get(value);
        return mapped != null ? mapped : value;
    }
}