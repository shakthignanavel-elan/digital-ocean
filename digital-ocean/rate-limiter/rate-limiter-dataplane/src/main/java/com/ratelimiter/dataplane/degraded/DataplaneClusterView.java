package com.ratelimiter.dataplane.degraded;

/**
 * Provides the last-known count of active dataplane nodes for degraded partitioning.
 */
public interface DataplaneClusterView {

    /**
     * Active dataplane count used when Redis is down. Always &gt;= 1.
     */
    int activeNodeCount();

    /**
     * Stable id for this dataplane process (used for heartbeats).
     */
    String nodeId();
}
