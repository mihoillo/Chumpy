# Chumpy for Windows — early port

The Windows desktop app is a separate module. The existing Android `:app` is retained unchanged.

## First milestone

- Kotlin/JVM desktop shell.
- Local list of imported share-link lines in `%LOCALAPPDATA%\\Chumpy\\profiles.txt`.
- Starts an external native core using a supplied JSON configuration.
- System-proxy mode backs up the current user's Windows proxy settings and restores them on disconnect.
- TUN mode requires an external sing-box executable, a compatible sing-box TUN config, Wintun, and Administrator rights.

## Not feature-complete yet

Imported links are stored but are not yet parsed into editable profiles or converted into engine configurations. The user currently supplies a native core and its matching JSON file. Android's `libv2ray.aar`, `VpnService`, Room database and Android UI are not Windows binaries.

Next: port the share-link parser and profile model, implement config generation, then add subscriptions, QR import, traffic statistics, themes and settings. Xray and sing-box configs are not interchangeable.

## Build (JDK 21)

```powershell
.\\gradlew.bat :windows-app:run
.\\gradlew.bat :windows-app:installDist
```
