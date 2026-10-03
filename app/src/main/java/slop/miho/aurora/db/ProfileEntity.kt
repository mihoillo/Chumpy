package slop.miho.aurora.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.security.MessageDigest

/** Proxy protocol kinds a profile can carry; the name is what the Xray config uses. */
enum class ProfileType {
    VMESS,
    VLESS,
    TROJAN,
    SHADOWSOCKS,
    SOCKS,
    WIREGUARD,
    HYSTERIA2,

    /** Not a server: a routing balancer over the subscription's other profiles. */
    BALANCER,
}

/**
 * One server, imported from a subscription link or a manual paste. The columns follow the
 * link formats' own vocabulary (transport in `network`/`host`/`path`, security in
 * `security`/`sni`/…), flat and denormalized on purpose: a profile is always read whole,
 * and updates replace the whole set of rows for a subscription.
 */
@Entity(
    tableName = "profiles",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["subscriptionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("subscriptionId")],
)
data class ProfileEntity(
    @PrimaryKey val id: String,
    /** Owning subscription, or null for a manually pasted profile. */
    val subscriptionId: String?,
    val type: ProfileType,
    val remarks: String = "",
    /** Position within the subscription, kept stable across updates. */
    val order: Long = 0,

    // Endpoint and authentication.
    val server: String = "",
    val serverPort: Int = 0,
    /** VMess UUID, VLESS id, Trojan/SS/Hy2 password, SOCKS pass. */
    val password: String = "",
    /** SS cipher; VMess security; VLESS encryption. */
    val method: String = "",
    /** SOCKS user. */
    val username: String = "",

    // Transport.
    val network: String = "tcp",
    val headerType: String = "",
    val host: String = "",
    val path: String = "",
    /** KCP seed. */
    val seed: String = "",
    /** gRPC service name. */
    val serviceName: String = "",
    /** gRPC authority. */
    val authority: String = "",
    /** gRPC multi / XHTTP mode. */
    val mode: String = "",
    /** XHTTP extra JSON, passed through verbatim. */
    val xhttpExtra: String? = null,

    // TLS / REALITY.
    /** "", "tls" or "reality". */
    val security: String = "",
    val sni: String = "",
    /** Comma-separated ALPN list, as links carry it. */
    val alpn: String = "",
    val fingerprint: String = "",
    val flow: String = "",
    val allowInsecure: Boolean = false,
    /** REALITY public key (WireGuard peer key on WIREGUARD profiles). */
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",

    // Hysteria2.
    val obfsPassword: String = "",
    val portHopping: String = "",
    val portHoppingInterval: String = "",

    // WireGuard.
    val wgSecretKey: String = "",
    val wgPresharedKey: String = "",
    /** Comma-separated interface addresses. */
    val wgLocalAddress: String = "",
    val wgMtu: Int = 0,
    /** "1,2,3"-style client reserved bytes. */
    val wgReserved: String = "",

    // Balancer (BALANCER profiles only).
    /** The Xray strategy: random, roundrobin, leastping or leastload. */
    val balancerStrategy: String = "",
    /** The members' linkKeys, newline-joined; resolved against the subscription's rows. */
    val balancerMembers: String = "",

    // Measurements.
    /** The last real ping through the core, in millis; null when never measured. */
    val testDelayMillis: Long? = null,
    val lastTestAt: Long? = null,

    /**
     * A digest of everything that defines the server. Subscription updates diff on it: a
     * profile whose linkKey survives an update keeps its identity, its selection state and
     * its last ping.
     */
    val linkKey: String = "",
)

/** The canonical digest used for update diffing; see [linkKey]. */
fun ProfileEntity.computeLinkKey(): String {
    // A balancer is its name and its strategy, not its members: the panel reshuffles the
    // members between updates, and the balancer — its row's identity, the selection
    // riding on it — is meant to survive that, as BetterExclave's by-name updates do.
    val canonical = if (type == ProfileType.BALANCER) {
        listOf(type.name, remarks, balancerStrategy)
    } else {
        listOf(
            type.name,
            server,
            serverPort.toString(),
            password,
            method,
            username,
            network,
            headerType,
            host,
            path,
            seed,
            serviceName,
            authority,
            mode,
            xhttpExtra.orEmpty(),
            security,
            sni,
            alpn,
            fingerprint,
            flow,
            allowInsecure.toString(),
            publicKey,
            shortId,
            spiderX,
            obfsPassword,
            portHopping,
            portHoppingInterval,
            wgSecretKey,
            wgPresharedKey,
            wgLocalAddress,
            wgMtu.toString(),
            wgReserved,
        )
    }.joinToString("\u0001")
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/** The balancer's member linkKeys, in column order. */
val ProfileEntity.balancerMemberKeys: List<String>
    get() = balancerMembers.split('\n').filter { it.isNotBlank() }
