package ocervinka.plcmqttbridge.functions;

import java.text.DecimalFormat;
import java.util.function.Function;

public class Decimals implements Function<String, String> {
    private final DecimalFormat decimalFormat;

    public Decimals(int decimals) {
        this.decimalFormat = new DecimalFormat(getDecimalPattern(decimals));
    }

    @Override
    public String apply(String input) {
        try {
            double number = Double.parseDouble(input);  // Convert string to number
            return decimalFormat.format(number);  // Round and return as string
        } catch (NumberFormatException e) {
            return input;  // If not a number, return as-is
        }
    }

    private static String getDecimalPattern(int decimals) {
        return "0." + "0".repeat(Math.max(0, decimals));
    }
}
