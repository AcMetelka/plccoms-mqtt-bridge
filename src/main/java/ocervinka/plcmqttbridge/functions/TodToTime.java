package ocervinka.plcmqttbridge.functions;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

public class TodToTime implements Function<String, String> {
    private static final long MILLIS_PER_DAY = 86_400_000L;

    @Override
    public String apply(String input) {
        final long millis;
        try {
            millis = Long.parseLong(input);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid TOD milliseconds: " + input, e);
        }
        if (millis < 0 || millis >= MILLIS_PER_DAY) {
            throw new IllegalArgumentException("TOD milliseconds must be between 0 and 86399999: " + input);
        }
        return LocalTime.ofNanoOfDay(millis * 1_000_000L).format(DateTimeFormatter.ISO_LOCAL_TIME);
    }
}
