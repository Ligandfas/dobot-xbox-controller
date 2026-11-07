# Nova 5 Xbox Control (Android)

Port of `nova5_xbox_control.py`. Same button mapping, same Dobot TCP-IP commands
(dashboard port 29999, motion port 30003), no third-party libraries.

## Build
1. Install Android Studio. Open this folder (File > Open).
2. Let Gradle sync (it downloads Gradle 8.7 and the Android SDK pieces it needs), then Build > Build Bundle(s)/APK(s) > Build APK(s).
   The debug APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.
3. Copy it to the phone and install (allow "install unknown apps"), or press Run
   with the phone connected over USB debugging.

## Use
1. Join the robot's network (Wi-Fi, or USB-Ethernet adapter). Put the Nova 5 in
   TCP/IP remote-control mode on the pendant.
2. Pair/plug in the Xbox controller (Bluetooth or USB-OTG).
3. Leave Auto-connect on: the app finds the robot on the phone's Wi-Fi/Ethernet
   network (port 29999, checked with a read-only RobotMode() query) and connects.
   It tries the last IP and the Dobot defaults (192.168.5.1, 192.168.1.6,
   192.168.2.6, 192.168.9.1) first, then scans the subnet. "Find" searches once on
   demand. Or type the IP and tap Connect. Auto-connect never enables the robot.
4. Press Start on the pad to enable. The big red button disables and stops jog.

## Controls (unchanged from the Python script)
Start=enable, Back/View=disable, Y=Cartesian/Joint, B=clear error, A/X=run scripts,
L3=stop script, R3=pause/continue, D-pad up/down=speed +/-10%,
D-pad left/right=J3, left stick=X,Y (J1,J2), right stick=Z,Rz (J3,J4),
LT/RT=Rx or J5, LB/RB=Ry or J6.

## Safety behaviour
The app disconnects (sending MoveJog()) when it goes to the background, when the
controller disconnects, or when the connection drops. Keep the e-stop in reach.

## If something is off
- Right stick moves the wrong axes: some pads use AXIS_RX/RY; both are handled,
  but tell me the pad model if it still misbehaves.
- No connection while Wi-Fi shows "no internet": the app binds to Wi-Fi/Ethernet
  explicitly; also try turning mobile data off.

## Build without Android Studio (GitHub Actions)
1. Create a new GitHub repository and upload the contents of this folder
   (keep the `.github/workflows` folder).
2. Open the repo's Actions tab. The "Build APK" workflow runs on every push
   (or press Run workflow).
3. When it finishes (about 3-5 minutes), open the run and download the
   `nova5-xbox-control-apk` artifact. Unzip it to get `app-debug.apk`.
