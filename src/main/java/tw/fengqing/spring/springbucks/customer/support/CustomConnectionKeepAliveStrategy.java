package tw.fengqing.spring.springbucks.customer.support;

import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.ConnectionKeepAliveStrategy;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;

public class CustomConnectionKeepAliveStrategy implements ConnectionKeepAliveStrategy {
    private final long DEFAULT_SECONDS = 30;

    @Override
    public TimeValue getKeepAliveDuration(HttpResponse response, HttpContext context) {
        long milliseconds = Arrays.stream(response.getHeaders("Connection"))
                .filter(h -> StringUtils.equalsIgnoreCase(h.getName(), "timeout")
                        && StringUtils.isNumeric(h.getValue()))
                .findFirst()
                .map(h -> NumberUtils.toLong(h.getValue(), DEFAULT_SECONDS))
                .orElse(DEFAULT_SECONDS) * 1000;
        
        return TimeValue.ofMilliseconds(milliseconds);
    }
}
