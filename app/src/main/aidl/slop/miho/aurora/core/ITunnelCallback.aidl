package slop.miho.aurora.core;

oneway interface ITunnelCallback {
    /** @param state ordinal of {@code TunnelState}; @param error message of the last failure, if any. */
    void onStateChanged(int state, String error);
}
