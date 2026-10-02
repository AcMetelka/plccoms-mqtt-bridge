package ocervinka.plcmqttbridge.functions;

import ocervinka.plcmqttbridge.config.VarMappingConfig;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TimeOfDayConversionTest {
    private final TodToTime todToTime = new TodToTime();
    private final TimeToTod timeToTod = new TimeToTod();

    @Test
    public void convertsKnownPlcValues() {
        assertEquals("00:00:00", todToTime.apply("0"));
        assertEquals("05:00:00", todToTime.apply("18000000"));
        assertEquals("18:02:00", todToTime.apply("64920000"));
        assertEquals("23:59:59", todToTime.apply("86399000"));
    }

    @Test
    public void convertsTimeToPlcMilliseconds() {
        assertEquals("0", timeToTod.apply("00:00:00"));
        assertEquals("18000000", timeToTod.apply("05:00:00"));
        assertEquals("64920000", timeToTod.apply("18:02:00"));
        assertEquals("86399000", timeToTod.apply("23:59:59"));
    }

    @Test
    public void convertersAreRegistered() {
        assertEquals("18:02:00", VarMappingConfig.getFunction("TodToTime").apply("64920000"));
        assertEquals("64920000", VarMappingConfig.getFunction("TimeToTod").apply("18:02:00"));
    }

    @Test
    public void rejectsInvalidPlcValues() {
        assertIllegalArgument(() -> todToTime.apply("-1"));
        assertIllegalArgument(() -> todToTime.apply("86400000"));
        assertIllegalArgument(() -> todToTime.apply("not-a-number"));
    }

    @Test
    public void rejectsInvalidTimeValues() {
        assertIllegalArgument(() -> timeToTod.apply("6:30:00"));
        assertIllegalArgument(() -> timeToTod.apply("06:30"));
        assertIllegalArgument(() -> timeToTod.apply("24:00:00"));
        assertIllegalArgument(() -> timeToTod.apply("12:60:00"));
        assertIllegalArgument(() -> timeToTod.apply("12:00:00.500"));
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
