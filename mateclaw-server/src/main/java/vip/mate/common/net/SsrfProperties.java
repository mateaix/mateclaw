package vip.mate.common.net;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared SSRF guard configuration consulted by every outbound-request tool
 * (browser navigation, hook webhooks, image download).
 *
 * <p>By default outbound guards block loopback, private, link-local and
 * cloud-metadata targets. {@link #ssrfAllowlist} lets an administrator reach a
 * specific internal host through all of those guards at once. Each entry is a
 * literal hostname, a literal IP, or an IPv4 CIDR block — see {@link SsrfAllowlist}.
 * Keep the list as narrow as possible; entries here can re-expose
 * cloud-metadata endpoints too.
 */
@Data
@Component
@ConfigurationProperties(prefix = "mateclaw.security")
public class SsrfProperties {

    /** Hosts/IPs/CIDR blocks permitted through the SSRF guards despite being otherwise restricted. */
    private List<String> ssrfAllowlist = new ArrayList<>();
}
