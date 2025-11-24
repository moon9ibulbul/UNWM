# AstralUNWM Windows build

This directory contains a Compose for Desktop adaptation of AstralUNWM that mirrors the Android feature set for Windows PCs. The GitHub Actions workflow (`.github/workflows/windows-build.yml`) packages the desktop UI into a Windows `.exe` installer using the `packageReleaseExe` task. No binaries are checked into the repository; outputs are produced during CI and uploaded as build artifacts.

## Running locally

1. Install JDK 17 and Gradle 8.7 or newer.
2. Ensure OpenCV native libraries can be resolved on your system (the build pulls `org.openpnp:opencv:4.10.0-0` and loads the packaged Windows binaries automatically).
3. From the `windows` directory run:
   ```bash
   gradle run
   ```
   To produce a Windows installer from Windows hosts run:
   ```bash
   gradle packageReleaseExe
   ```

The desktop application preserves unwatermarking, detection, and extraction tools with a responsive layout suitable for desktop screens.
