package uz.nodir.ratelimitapigateway.model.property;

import lombok.Data;

/**
 * Author:Nodir
 * User:smart
 * Date:13.08.2025
 * Time:11:57 AM
 */


@Data
public class ResponseCacheProperty {
    private long ttlSeconds;
    private int maxBytes;
    private boolean only200;
    private boolean jsonOnly;
    private boolean addCacheHeader;
    private boolean keyByIntegrator;
}
