package com.bilal.neobank.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceLabelTest {

    private static final String CHROME_WINDOWS = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final String EDGE_WINDOWS = CHROME_WINDOWS + " Edg/126.0.0.0";
    private static final String OPERA_WINDOWS = CHROME_WINDOWS + " OPR/111.0.0.0";
    private static final String FIREFOX_LINUX = "Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0";
    private static final String SAFARI_MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15";
    private static final String SAFARI_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";
    private static final String CHROME_IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.153 Mobile/15E148 Safari/604.1";
    private static final String CHROME_ANDROID = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    @Test
    void recognisesTheCommonBrowserAndSystemPairs() {
        assertThat(DeviceLabel.of(CHROME_WINDOWS)).isEqualTo("Chrome on Windows");
        assertThat(DeviceLabel.of(FIREFOX_LINUX)).isEqualTo("Firefox on Linux");
        assertThat(DeviceLabel.of(SAFARI_MAC)).isEqualTo("Safari on macOS");
        assertThat(DeviceLabel.of(SAFARI_IPHONE)).isEqualTo("Safari on iOS");
        assertThat(DeviceLabel.of(CHROME_ANDROID)).isEqualTo("Chrome on Android");
    }

    @Test
    void browsersThatPretendToBeOthersAreToldApart() {
        assertThat(DeviceLabel.of(EDGE_WINDOWS)).isEqualTo("Edge on Windows");   // also says "Chrome"
        assertThat(DeviceLabel.of(OPERA_WINDOWS)).isEqualTo("Opera on Windows"); // also says "Chrome"
        assertThat(DeviceLabel.of(CHROME_IPHONE)).isEqualTo("Chrome on iOS");    // "CriOS", and "like Mac OS X"
        assertThat(DeviceLabel.of(CHROME_ANDROID)).doesNotContain("Linux");      // Android also says "Linux"
    }

    @Test
    void toolsAndUnknownClientsStillGetAReadableLabel() {
        assertThat(DeviceLabel.of("curl/8.5.0")).isEqualTo("curl");
        assertThat(DeviceLabel.of("SomeBrowser/1.0 (Windows NT 10.0)")).isEqualTo("Windows");
        assertThat(DeviceLabel.of("totally-unknown")).isEqualTo("Unknown device");
    }

    @Test
    void missingHeadersGiveTheUnknownLabel() {
        assertThat(DeviceLabel.of(null)).isEqualTo("Unknown device");
        assertThat(DeviceLabel.of("")).isEqualTo("Unknown device");
        assertThat(DeviceLabel.of("   ")).isEqualTo("Unknown device");
    }

    @Test
    void theLabelNeverContainsMoreThanTheRecognisedPartsSoTheRawHeaderIsNotKept() {
        String hostile = "Mozilla/5.0 (Windows NT 10.0) Chrome/1 " + "x".repeat(5000) + " secret-token=abc123";
        String label = DeviceLabel.of(hostile);
        assertThat(label).isEqualTo("Chrome on Windows").doesNotContain("secret").hasSizeLessThanOrEqualTo(60);
    }
}
