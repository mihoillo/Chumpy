package slop.miho.chumpy.core;

import slop.miho.chumpy.core.ITunnelCallback;

interface ITunnelService {
    /**
     * Brings the VPN up (or reloads the running core in place) using the config file the
     * UI process wrote to the core directory. Asynchronous; progress arrives via callbacks.
     */
    void connect();

    void disconnect();

    /** Wall-clock millis of the last transition to UP, 0 when down (survives UI restarts). */
    long connectedAtMillis();

    /** The tunnel's traffic counters since it came up: {rxBytes, txBytes}; zeros while down. */
    long[] trafficBytes();

    /**
     * Measures the real delay through the given complete Xray config (one tested outbound,
     * no inbounds) in the core process. Returns the delay in millis, or -1 on failure.
     */
    long measureDelay(String configJson, String testUrl);

    /**
     * Measures the delay through the *running* core's tunnel, fetching [testUrl] through
     * the live proxy outbound. Returns millis, or -1 when the core is not running.
     */
    long measureLiveDelay(String testUrl);

    /** The Xray core version string, for the about screen. */
    String coreVersion();

    /** Registers a listener; it immediately receives the current state. */
    void registerCallback(ITunnelCallback callback);

    void unregisterCallback(ITunnelCallback callback);
}
