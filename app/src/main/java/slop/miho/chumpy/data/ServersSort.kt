package slop.miho.chumpy.data

import slop.miho.chumpy.R
import slop.miho.chumpy.db.ProfileEntity

/**
 * The servers list's own order, the header's chip picks it. The pinned rows keep the
 * ground floor under every mode — the pin is the list's law, not a mode's suggestion;
 * whatever the mode, it only orders what stands above the pins' floor. The default is
 * the panel's own birthday order, the way the list always kept it.
 */
enum class ServersSort(val labelRes: Int) {
    /** The panel's own order, position within the subscription. */
    DEFAULT(R.string.servers_sort_default),
    /** By name, case folded — the flag stripped off is the row's own display's business. */
    NAME(R.string.servers_sort_name),
    /**
     * By the last real ping; never-measured and unreachable rows share the bottom —
     * both carry a null delay, and the bottom is where a null belongs.
     */
    PING(R.string.servers_sort_ping),
    /** By the last pick as the connect's profile, the freshest hand first. */
    LAST_USED(R.string.servers_sort_last_used),
}

/**
 * The mode's whole law in one comparator: pins first, then the mode's own key, then the
 * panel's own order as the tiebreak so no two rows ever trade places over a coin flip.
 */
fun ServersSort.comparator(): Comparator<ProfileEntity> = when (this) {
    ServersSort.DEFAULT -> compareBy({ !it.pinned }, { it.order }, { it.remarks })
    ServersSort.NAME -> compareBy({ !it.pinned }, { it.remarks.lowercase() }, { it.order })
    ServersSort.PING -> compareBy(
        { !it.pinned },
        { it.testDelayMillis ?: Long.MAX_VALUE },
        { it.order },
    )
    ServersSort.LAST_USED -> compareBy<ProfileEntity> { !it.pinned }
        .thenByDescending { it.lastUsedAt ?: Long.MIN_VALUE }
        .thenBy { it.order }
}
