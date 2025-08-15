package uz.nodir.ratelimitapigateway.model.property;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Author:Nodir
 * User:smart
 * Date:13.08.2025
 * Time:9:51 AM
 */

@Data
public class JsonRateLimitProperty {
    private int limit;
    private int windowSeconds;
    private int statusCode;
    private boolean requireIntegratorId;
    private int missingIntegratorStatus;
    private String missingIntegratorCode;
    private String missingIntegratorMessage;
    private String failedCode;
    private String failedMessage;
}
