# Test devices

Hardware used to validate this fork. Personal identifiers (device display name, Bluetooth address, serial numbers) are withheld.

## Phone

| Item | Value |
|---|---|
| Model | OnePlus Ace 3 (`PJE110`, device `OP5CF9L1`) |
| SoC | Qualcomm Snapdragon 8 Gen 2 (`SM8550`) |
| RAM | ~10.9 GiB usable |
| Display | 1264 x 2780, 560 dpi, 120 Hz (60 / 90 / 120 modes) |
| OS | Android 16 (API 36), build `PJE110_16.0.5.1002(CN01)` |
| Security patch | 2026-07-01 |
| ABI | arm64-v8a |

## Earbuds

| Item | Value |
|---|---|
| Model | AirPods 5 |
| Model number | `A3533` (family `A3531` / `A3532` / `A3533`) |
| Reported versions | Version 1 `90.3431000025000000.7362`, Version 2 `90.3431000025000000.7362`, Version 3 `9442784` |
| Transport | BR/EDR, absolute volume supported |

Version 1 and Version 2 are reported identically; Version 3 is a separate field. All three come from the AACP device information packet (opcode `0x001D`), field mapping documented in [device-info.md](device-info.md).

## App under test

| Item | Value |
|---|---|
| Package | `io.github.hunric.librepods` (this fork); upstream `me.kavishdevar.librepods` |
| Version | `1.0.4` (versionCode `100040301`) |
| Flavor / build type | `foss` / `release` |
| minSdk / targetSdk | 33 / 37 |

## What these devices were used for

- Overlay animation timing: cold connect popup and Dynamic Island entrance/exit frames, measured with Perfetto (`doFrame`, `traversal`, `draw`, `notifyAnimEnd`).
- Settings sync: device-authoritative adoption on first connect, optimistic writes for keys the device does not report, wear-gated write queue for listening mode.
- Signature and package migration: debug-signed test builds to release-signed distribution.
