# Privacy hardening for PDRM00 (LineageOS 23.2)

Implemented 2026-11-08/09 on top of the `lineage_hima-trunk_staging-userdebug` tree.
Everything below was compiled, flashed, and verified on the device unless a
section says otherwise.

## What it does

### 1. Per-app device identifier virtualisation

Registered applications get stable fake identifiers derived from a per-device
**epoch** (a 128-bit random value the privacy centre regenerates whenever the
user re-arms the feature). Flipping the switch off and on hands the app a whole
new identity without reinstalling anything.

| API | Source |
|---|---|
| `TelephonyManager.getImei(int)` | `TelephonyManager.java` |
| `getDeviceId()` / `getDeviceId(int)` |  |
| `getTypeAllocationCode(int)` | consistent with the fake IMEI prefix |
| `getSubscriberId(int)` |  |
| `getSimSerialNumber(int)` |  |
| `getMeid()` / `getMeid(int)` | AOSP returns null; opted-in apps get a fake |
| `Build.getSerial()` | `Build.java` |
| `Settings.Secure.ANDROID_ID` | `SettingsProvider.getSsaidSettingLocked` |
| `MediaDrm.getPropertyByteArray(PROPERTY_DEVICE_UNIQUE_ID)` | `MediaDrm.java` |

Derivation: `SHA-256(epoch | packageName | field)`, shape-preserving — 15 digits
with a valid Luhn check digit for IMEI, 16 lowercase hex for the SSAID, and so on.

**Why the "fake on deny" path exists:** for an unprivileged caller AOSP throws
`SecurityException` inside `PhoneSubInfoController` before any value is returned,
so wrapping the return value alone was useless (measured: identical output with
the switch on and off). The framework now catches `SecurityException` at the
client call site and substitutes a fake value for opted-in packages.

**Salting note:** the epoch is *not* derived from `ANDROID_ID`. Using it as a
salt would make the SSAID virtualisation self-referential, and it is per-uid
anyway (`SettingsProvider.java:1781-1783`), so it is not a secret.

### 2. Whole-device model spoofing

The privacy centre offers four SM8250 profiles (OnePlus 8 / 8 Pro variants),
stored in `Settings.Global.device_profile_*`. At boot, after
`installSystemProviders()`, system_server copies them into
`persist.sys.hima.spoof.*`; on the **next** boot init loads those properties
before zygote starts, and `Build` (initialised inside zygote) reports them.

Spoofed fields: `MODEL`, `BRAND`, `DEVICE`, `PRODUCT`, `MANUFACTURER`,
`FINGERPRINT`. `HARDWARE` is deliberately left alone — `ro.hardware` locates HAL
implementations.

**Two reboots are required, and this cannot be avoided.** `ro.*` properties are
write-once (`property_service.cpp:400-401`) and system_server only runs *after*
zygote has already initialised `Build`, so the value has to be persisted on one
boot and consumed on the next.

**This is whole-device, not per-app.** `android.os.Build` is in the zygote
preload list (`frameworks/base/config/preloaded-classes:6400`) and is first
touched in `RuntimeInit.getDefaultUserAgent()` before any process identity is
available, so every app sees the same spoofed model. Per-app model spoofing is
not achievable without breaking binary compatibility on `Build`'s `static final`
fields.

### 3. Other privacy controls

- Per-app internet firewall (`NetworkPolicyManagerService.setUidInternetBlocked`,
  using the `FIREWALL_CHAIN_RESTRICTED` chain, with `Binder.clearCallingIdentity()`
  so netd accepts the call).
- Background-run restriction via `AppOpsManager.OP_RUN_IN_BACKGROUND`.
- App lock: an accessibility service gates selected apps behind the device
  credential.
- Sensor privacy (camera/microphone) via `SensorPrivacyService`, with the privacy
  centre added to a trusted-package list instead of `sharedUserId=android.uid.system`.

## Verification performed on device

Identity virtualisation, before vs after enabling (unprivileged probe app,
`READ_PHONE_STATE` revoked):

```
                        off                          on (epoch A)
Build.SERIAL            SecurityException            A0EC1617892E6FC2
getImei()               SecurityException            424536396297930
getTypeAllocationCode() 86213705  (real leak!)        42453629
getSubscriberId()       SecurityException            431783745314845
getSimSerialNumber()    SecurityException            59137815123377981486
getMeid()               null                         2E0E7ED5C97088
```

The same epoch read twice returns identical values; switching to epoch B changes
all of them. `getTypeAllocationCode()` previously leaked the **real** TAC to
unprivileged apps because the server does not permission-check it.

Whole-device spoof after selecting OnePlus 8 (IN2015) through the UI and
rebooting twice:

```
MODEL = IN2015   BRAND = OnePlus   DEVICE = OnePlus8
FINGERPRINT = OnePlus/OnePlus8/OnePlus8:11/RP1A.201005.001/2110102308:user/release-keys
```

`getprop ro.product.model` still returns `PDRM00` — only `Build` is redirected.

## Defects found and fixed during bring-up

1. `ro.hima.spoof.*` → `persist.sys.hima.spoof.*` (ro is write-once and set too late).
2. The sync call ran before `mSystemContext` was assigned; moved after
   `installSystemProviders()`.
3. `writeProfile()` passed `"device_profile"` to a helper that prepends
   `"device_profile_"`, producing `device_profile_device_profile`; the picker
   could never read its own selection back.
4. Profile rows had no click listener, so taps did nothing.
5. `SPOOF_PROP_*` constants were declared after their first use (harmless only
   because javac inlines constant variables).
6. `SettingsProvider` would re-enter itself while holding `mLock`
   (`getSsaidSettingLocked` → `Settings.Secure.getStringForUser`); the epoch is
   now passed in from the provider's own registry instead.
7. Unguarded `SparseBooleanArray.delete()` return type, an invalid
   `deprecated|signature` protectionLevel, and an unescaped apostrophe in
   `strings.xml` — all compile blockers.

## Deliberately not done

- **Hiding installed packages** from the query API. It touches 80+ call sites in
  `ComputerEngine`, and the failure mode is apps silently degrading rather than
  erroring, which is hard to diagnose. Would also hide icons from the launcher.
- **Network-stack fingerprinting.** AOSP already blocks what matters: physical
  MACs are not exposed (`NetworkInterface.java:605`), `/sys/class/net` is
  `neverallow`ed for untrusted apps, and TCP/UDP tables are closed off.
  Intercepting `NetworkInterface` in libcore would break VPNs and connectivity
  checks.
- **Font/sensor enumeration.** Font entropy is a few bits at most. Sensors should
  be disabled per-type through the existing `SensorPrivacyService`, not virtualised.
- **eSIM EID.** This device has no euicc package or node.

## Known limitations

- Security patch level is `2025-11-05` (vendor `2025-08-01`), a year behind.
  This is inherited from the AOSP tag, not introduced here. Changing the
  *displayed* value would be misreporting; fixing it means following an AOSP
  security branch.
- Virtualised identifiers are only verified at the API level. No regression
  testing of banking or DRM apps was performed.
- MediaDrm spoofing will break Widevine device binding (Netflix, Disney+ and
  similar) for opted-in packages.

## Files

- `patches/privacy-20261109/04-frameworks-base-privacy.diff` — framework changes
- `patches/privacy-20261109/new-files/` — `IdentifierVirtualizer.java` (new, untracked)
- `patches/privacy-20261109/05-device-hima.diff`, `06-device-sm8250-common.diff`
- `apps/HimaPrivacy/` — the privacy centre (platform-signed, system_ext priv-app)
- `apps/PrivacyProbe/` — unprivileged probe app used for the measurements above
