package slop.miho.aurora.core;

import slop.miho.aurora.core.ITunnelCallback;

interface ITunnelService {
    /** Brings the tunnel up using the imported config. Asynchronous; progress arrives via callbacks. */
    void connect();

    void disconnect();

    /** Wall-clock millis of the last transition to UP, 0 when down (survives UI restarts). */
    long connectedAtMillis();

    /** The tunnel's traffic counters since it came up: {rxBytes, txBytes}; zeros while down. */
    long[] trafficBytes();

    /** Registers a listener; it immediately receives the current state. */
    void registerCallback(ITunnelCallback callback);

    void unregisterCallback(ITunnelCallback callback);
}
