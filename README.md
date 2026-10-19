<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="icon.svg">
    <img src="icon-inverted.svg" width="160" alt="Chumpy" />
  </picture>
</p>

<h1 align="center">Chumpy</h1>

<p align="center">
  Yet another Xray client for VLESS and friends — dressed in Material Design 3 Expressive.
</p>

> **Alpha.** Still under development. If it crashes on you — and it might —
> please report it in the [Issues tab](https://github.com/mihoillo/Chumpy/issues).

## What's inside

**Protocols & configs**
- Outbounds: VLESS, VMess, Trojan, Shadowsocks, SOCKS, Hysteria2, WireGuard
- Share links: `vless://`, `vmess://`, `trojan://`, `ss://`, `socks5://`,
  `hysteria2://`, `wireguard://`, and WireGuard `.conf` bodies
- Subscriptions: base64 link lists, Clash YAML, raw Xray JSON configs
- Balancers: one row over many servers — random, round robin, least ping
  or least load
- Add by paste, QR code or URL; subscriptions refresh in the background
- Happ client spoofing for panels that serve configs depending on who's asking

**The tunnel**
- Per-app bypass: the apps you pick stay out of the tunnel
- Routing: global, or keep chosen countries and sites direct
- Re-dials on every network change, no questions asked
- Auto-reconnect after a fall — a toggle, your call
- Steps aside for a foreign VPN, takes the slot back when it's gone
- Delay test per server

**The face**
- Material 3 Expressive: wallpaper colors, frosted glass, custom accents
  (rainbow included)
- Russian and English; per-app language on Android 13+
- Quick Settings tile

## Requirements

Android 8.0 (API 26) or newer.

## Install

Per-ABI APKs (arm64-v8a, armeabi-v7a) land in the Telegram channel:
[t.me/ChumpyXray](https://t.me/ChumpyXray).

## Building

```shell
./gradlew :app:assembleRelease
```

Build numbers count themselves up from `build_number.properties`.
Signing reads `keystore.properties` at the repo root (not committed);
without it the build falls back to the debug key.

## Thanks to

- [Xray-core](https://github.com/XTLS/Xray-core) +
  [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) — the engine
- [v2rayNG](https://github.com/2dust/v2rayNG) — share-link parsers ported
  from its `fmt/` package (Apache-2.0)
- [zxing](https://github.com/zxing/zxing) — QR scanning
- [Material Symbols](https://fonts.google.com/material) — the icons
- [Twemoji](https://github.com/jdecked/twemoji) — the flags (CC-BY 4.0)

## License

MIT — see [LICENSE](LICENSE).
