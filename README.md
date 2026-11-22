# R10 Private Range · Android 0.2.0-alpha

Private, offline-first Android driving range for a **Garmin Approach R10**. All app data stays on the phone. No remote account, SDK subscription, or `INTERNET` permission.

> **Development status:** The entire BLE/GFDI/protobuf connection path has been implemented in Java and passed *synthetic protocol regression tests*. It has **not been installed, compiled as an APK, or verified against a physical R10 on Android** in this environment. The remaining hardware compatibility work is important; do not assume live-shot support works until tested on your R10.

## What changed in v0.2

* Discover an `Approach R10` by BLE name; request Android Bluetooth bond if necessary; use native GATT connect.
* Discover the Garmin MultiLink GATT service, enable notifications, write to the correct pair of characteristics.
* Queue serialized GATT writes (using ATT-safe 20-byte values and explicit timeouts).
* Send MultiLink service-1 registration and handle its 16-byte response.
* Assemble notification fragments; COBS decode; verify CRC16/ARC; parse GFDI standard/compressed message headers.
* Complete the documented GFDI 5024/5050 handshake; ACK optional FIT messages without persisting device identity.
* Subscribe to shot-data alerts, request radar wake-up, decode status transitions and shots from protobuf messages.
* Extract launch speed/angle/direction, spin/spin axis, club speed/face/path/attack, shot id, and spin provenance; ignore duplicate retransmissions for 60 s.
* Send decoded real-shot measurements to the in-app range UI, while **labeling carry and lateral distance as unvalidated estimates**.
* Keep demo shots completely separate and labeled `DEMO`.
* Export the extended shot fields to CSV. Raw BLE payloads / device IDs are not exported.
* Include repeatable pure-Java tests for the protocol phases.

### Known limitations

* The phone-to-R10 path and Android `MainActivity.java` have not been exercised with hardware or compiled into an APK in this environment. Testing will likely reveal firmware-dependent edge cases.
* Protobuf fragmentation is handled for single-frame shot messages only; multi-part reassembly is not implemented.
* No automatic reconnect yet. Use **R10 Connect → Scan** after a disconnect.
* Swing without a ball is recognized in diagnostics but not imported as a ball-flight shot.
* The R10 reports **launch** data; carry/roll/landing are estimated by the prototype's simple formula (not calibrated, not simulation-quality). Side-spin trajectory is not yet modelled.
* The selected club is assigned by the user in the app; the R10 doesn't automatically identify your club.
* No real golf-course environment yet. Driving range first.

## On-phone test plan

1. Charge and power on the R10. Close Garmin Golf and any other apps connected to it.
2. In Android settings, pair **Approach R10** over Bluetooth, if it is not already paired. (The app also asks Android to bond if needed.)
3. Install the generated debug APK using the build method below. Open the app and allow **Nearby devices** when requested.
4. Open **R10 Connect** → **Scan for R10**. Confirm progress through:
   * `Found Approach R10`
   * `Connected. Discovering R10 GATT services`
   * `Subscribing to R10 MultiLink notifications`
   * `Registering R10 MultiLink`
   * `GFDI handshake: ...`
   * `Handshake complete. Subscribing to shot data`
   * `Subscribed. Waking R10 radar`
   * `R10 radar: Armed — ready` or `R10 already awake — ready for shots`
5. Set your selected club on **Range**, hit a ball, and check for `Live shot #… received` in diagnostics and a new `R10`-labelled row in the range/history.
6. If it stalls, take a screenshot of the **connection diagnostics text** and share it, preferably without any Bluetooth MAC addresses or other private identifiers. The app deliberately does not expose binary packet captures.

If it does not find the device: ensure Garmin Golf is fully disconnected, turn R10 off/on, pair via Android Bluetooth settings, grant Nearby devices, then scan again. A hardware test will determine whether Android's no-location BLE scanning flag needs changing on your phone.

## APK build without a local IDE

The ZIP contains a complete Gradle source project, **not an APK**. To compile using GitHub's hosted build computers:

1. Create a **private** GitHub repository; upload the ZIP's **contents** into its root.
2. On GitHub, open **Actions → Build Android APK → Run workflow** (or push to `main`).
3. Wait for the build to finish; download the `r10-private-range-debug-apk` artifact and extract its `app-debug.apk`.
4. Open that APK on your Android phone and follow Android's prompts to allow installation from the app used to open it.

Alternatively, open the project in Android Studio with Android SDK 35 / JDK 17 / Gradle 8.9 and choose **Build APK(s)**. Neither method needs a Play Store listing.

## Run protocol regression tests without Android SDK

On a machine with a JDK:

```bash
bash tests/run-tests.sh
```

Tests simulate a full session: MultiLink registration → GFDI 5024/5050 → protobuf subscribe → wake response → decoded launch shot → duplicate suppression, also exercising COBS, CRC, split BLE notifications and corruption handling. These tests **do not constitute a hardware test**.

## Source code

* `app/src/main/java/com/privategolf/r10range/MainActivity.java` — Android BLE permissions, bond/connect, serialized writes, local WebView bridge, offline CSV export.
* `app/src/main/java/com/privategolf/r10range/R10Protocol.java` — pure Java Garmin MultiLink/GFDI/protobuf protocol driver.
* `app/src/main/assets/index.html` — offline range UI and practice history.
* `tests/R10ProtocolTest.java` — regression tests.
* `.github/workflows/android-apk.yml` — hosted build of debug APK.

## Interoperability references

This independent interoperability implementation uses publicly documented protocol behavior and original Java code. Useful technical references:

* [10over: Wire Protocol Reference](https://github.com/divotmaker/10over/blob/main/docs/WIRE.md)
* [10over: Connection Lifecycle and Message Sequencing](https://github.com/divotmaker/10over/blob/main/docs/SEQUENCE.md)
* [10over: R10 device library](https://docs.rs/crate/tenover/latest)
* [Flighthook: Garmin R10](https://github.com/divotmaker/flighthook/blob/main/docs/devices/garmin-r10.md)

No first-party Garmin code or authentication bypass is included. This project is not associated with Garmin.
