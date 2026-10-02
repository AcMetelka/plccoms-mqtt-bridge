package ocervinka.plcmqttbridge.functions;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Function;
import java.util.regex.Pattern;

public class TimeToTod implements Function<String, String> {
    private static final Pattern FORMAT = Pattern.compile("\\d{2}:\\d{2}:\\d{2}");

    @Override
    public String apply(String input) {
        if (!FORMAT.matcher(input).matches()) {
            throw new IllegalArgumentException("Time must use HH:MM:SS format: " + input);
        }
        final LocalTime time;
        try {
            time = LocalTime.parse(input, DateTimeFormatter.ISO_LOCAL_TIME);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid time: " + input, e);
        }
        return Long.toString(time.toNanoOfDay() / 1_000_000L);
    }
}
