package slop.miho.aurora.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import slop.miho.aurora.R
import slop.miho.aurora.db.SubscriptionEntity
import slop.miho.aurora.sub.HappSpoof
import slop.miho.aurora.ui.components.rememberHapticPoke
import java.util.Locale

/**
 * A subscription's own settings, one long press deep: the Happ spoof — the fingerprint
 * the panel's fetches go out with — and the way to the delete question. The identity
 * (user id and HWID) is generated the first time the spoof turns on and kept from then
 * on: panels bind accounts to it, so an accidental regeneration is a deliberate act,
 * the regenerate row's own tap.
 *
 * The fields sit in pairs where they are short, the device model and nothing else
 * earning the full width. Everything is local until Save carries it into the row; the
 * delete row only hands over to the delete confirmation, never acting on its own.
 */
@Composable
fun HappSpoofDialog(
    subscription: SubscriptionEntity,
    onSave: (SubscriptionEntity) -> Unit,
    onDeleteRequest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val poke = rememberHapticPoke()

    var enabled by remember(subscription.id) { mutableStateOf(subscription.happSpoof) }
    var appVersion by remember(subscription.id) { mutableStateOf(subscription.happAppVersion) }
    var os by remember(subscription.id) { mutableStateOf(subscription.happOs) }
    var osVersion by remember(subscription.id) { mutableStateOf(subscription.happOsVersion) }
    var deviceModel by remember(subscription.id) { mutableStateOf(subscription.happDeviceModel) }
    var locale by remember(subscription.id) { mutableStateOf(subscription.happLocale) }
    var userId by remember(subscription.id) { mutableStateOf(subscription.happUserId) }
    var hwid by remember(subscription.id) { mutableStateOf(subscription.happHwid) }

    fun prefillIdentity() {
        if (appVersion.isBlank()) appVersion = HappSpoof.DEFAULT_APP_VERSION
        if (os.isBlank()) os = "Android"
        if (osVersion.isBlank()) osVersion = HappSpoof.defaultOsVersion()
        if (deviceModel.isBlank()) deviceModel = HappSpoof.defaultDeviceModel()
        if (locale.isBlank()) locale = Locale.getDefault().language.take(2).ifBlank { "en" }
        if (userId.isBlank()) userId = HappSpoof.randomUserId()
        if (hwid.isBlank()) hwid = HappSpoof.randomHwid()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_device),
                contentDescription = null,
                tint = colors.primary,
            )
        },
        title = {
            Text(
                subscription.name.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.happ_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The master row: the app's own toggle language — the tile wears the
                // accent while the spoof is on, and the whole row carries the tap.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable {
                            poke()
                            enabled = !enabled
                            if (enabled) prefillIdentity()
                        }
                        .padding(vertical = 4.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (enabled) colors.primary else colors.secondaryContainer,
                        contentColor = if (enabled) colors.onPrimary else colors.onSecondaryContainer,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(
                                    if (enabled) R.drawable.ic_check else R.drawable.ic_device,
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            text = stringResource(R.string.happ_title),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                        Text(
                            text = stringResource(R.string.happ_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpoofField(
                            label = stringResource(R.string.happ_app_version),
                            value = appVersion,
                            onValueChange = { appVersion = it },
                            modifier = Modifier.weight(1f),
                        )
                        SpoofField(
                            label = stringResource(R.string.happ_locale),
                            value = locale,
                            onValueChange = { locale = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpoofField(
                            label = stringResource(R.string.happ_os),
                            value = os,
                            onValueChange = { os = it },
                            modifier = Modifier.weight(1f),
                        )
                        SpoofField(
                            label = stringResource(R.string.happ_os_version),
                            value = osVersion,
                            onValueChange = { osVersion = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SpoofField(
                        label = stringResource(R.string.happ_device_model),
                        value = deviceModel,
                        onValueChange = { deviceModel = it },
                    )

                    // The identity the panel binds the account to. A fresh pair is a
                    // deliberate act: the row's tap, not a passing toggle.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpoofField(
                            label = stringResource(R.string.happ_user_id),
                            value = userId,
                            onValueChange = { userId = it },
                            modifier = Modifier.weight(1f),
                        )
                        SpoofField(
                            label = stringResource(R.string.happ_hwid),
                            value = hwid,
                            onValueChange = { hwid = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CircleShape)
                            .clickable {
                                poke()
                                userId = HappSpoof.randomUserId()
                                hwid = HappSpoof.randomHwid()
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_renew),
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.happ_regenerate),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }

                TextButton(
                    onClick = onDeleteRequest,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) {
                    Text(stringResource(R.string.happ_delete))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        subscription.copy(
                            happSpoof = enabled,
                            happAppVersion = appVersion.trim(),
                            happOs = os.trim(),
                            happOsVersion = osVersion.trim(),
                            happDeviceModel = deviceModel.trim(),
                            happLocale = locale.trim(),
                            happUserId = userId.trim(),
                            happHwid = hwid.trim(),
                        ),
                    )
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.happ_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.happ_cancel))
            }
        },
    )
}

/** One fingerprint field, the dialog's own compact take on the settings screens' rows. */
@Composable
private fun SpoofField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
    )
}
