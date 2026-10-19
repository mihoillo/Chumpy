package slop.miho.chumpy.core

/** Tunnel lifecycle as seen by both processes. Sent over AIDL as [ordinal]. */
enum class TunnelState {
    DOWN,
    CONNECTING,
    UP,
    DISCONNECTING;

    companion object {
        fun fromOrdinal(ordinal: Int): TunnelState = entries.getOrElse(ordinal) { DOWN }
    }
}

data class TunnelStatus(
    val state: TunnelState = TunnelState.DOWN,
    val error: String? = null,
)
