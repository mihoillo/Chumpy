<img src="icon.svg" width="96" alt="Chumpy" />

# Chumpy

Yet another Xray client for VLESS and friends — dressed in Material Design 3 Expressive.

> **Alpha.** Still under development. If it crashes on you — and it might —
> please report it in the [Issues tab](https://github.com/mihoillo/Chumpy/issues).

## Requirements

Android 8.0 (API 26) or newer.

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
