# Flip Driver

A lightweight Android app for driving the LEGO Technic **42140 App-Controlled Transformation Vehicle**, as a
replacement for the 1 GB Control+ app. About 100 KB, no libraries, plain Kotlin and a single Canvas view.

Not affiliated with or endorsed by the LEGO Group.

## Features

- **Controllers:** PlayStation and other gamepads, plus touch sliders. Four stick layouts: tank, arcade (R2/L2 throttle), left stick only and right stick only. Press L2 + R2 to cycle between them.
- **Controls:** the D-pad drives, L1 and R1 turn by a set angle using the hub's gyro, □ or ○ brakes and △ cycles speed (Eco, Normal or Turbo).
- **Flip-aware:** controls stay relative to the driver when the vehicle drives upside down.
- **Heading hold:** keeps the vehicle driving straight using the hub's yaw.
- **Background driving:** a foreground service keeps the hub connected. With root, the controller keeps working while the app is in the background.
- **Controller haptics:** engine rumble, plus feedback for crashes, flips, turns, brakes and stalls. Each can be turned on or off.
- **Lights:** the hub light and the controller light bar can show battery, speed, orientation or layout.
- **Safety:** stall protection, constant-speed mode, acceleration ramps and tilt-to-steer.
- **Telemetry:** temperature, current, voltage, speed and distance.
- **Hub tools:** rename the hub and turn it off.

## Build

```sh
./gradlew assembleRelease
```

Requirements: Android 12 (API 31) or later, and Bluetooth LE.

To build a signed release, put a `keystore.properties` file and `release.jks` in the project root. Both are gitignored.

The root input helper lives in `app/src/main/c/evgrab.c`. It's built with the NDK, and the result ships as `jniLibs/arm64-v8a/libevgrab.so`.

## Protocol

The app talks to the hub over LEGO Wireless Protocol 3 on BLE, using service `00001623-1212-efde-1623-785feabcd123`.

The tracks are motors on ports 1 (left) and 0 (right, inverted). Both motors are paired into a virtual port, so one command drives them together.
