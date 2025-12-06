# Discontinued

This app is discontinued. The last release on Github and F-Droid will happen
with the December 2024 Syncthing version. Interactions (issues, PRs) are limited
now, and the entire repo will be archived after the last release. Thus all
contributions are preserved for any future (re)use. The forum is still open for
discussions and questions. I would kindly ask you to refrain from trying to
challenge the decision or asking "why-type" questions - I wont engage with them.

The reason is a combination of Google making Play publishing something between
hard and impossible and no active maintenance. The app saw no significant
development for a long time and without Play releases I do no longer see enough
benefit and/or have enough motivation to keep up the ongoing maintenance an app
requires even without doing much, if any, changes.

Thanks a lot to everyone who ever contributed to this app!

# syncthing-android

[![License: MPLv2](https://img.shields.io/badge/License-MPLv2-blue.svg)](https://opensource.org/licenses/MPL-2.0)

A wrapper of [Syncthing](https://github.com/syncthing/syncthing) for Android.

<img src="app/src/main/play/listings/en-GB/graphics/phone-screenshots/screenshot_phone_1.png" alt="screenshot 1" width="200" /> <img src="app/src/main/play/listings/en-GB/graphics/phone-screenshots/screenshot_phone_2.png" alt="screenshot 2" width="200" /> <img src="app/src/main/play/listings/en-GB/graphics/phone-screenshots/screenshot_phone_3.png" alt="screenshot 3" width="200" />

# Translations

The project is translated on [Hosted Weblate](https://hosted.weblate.org/projects/syncthing/android/).

## Dev

Language codes are usually mapped correctly by Weblate itself. The supported
set is different between [Google Play][1] and Android apps. The latter can be
deduced by what the [Android core framework itself supports][2]. New languages
need to be added in the repository first, then appear automatically in Weblate.

[1]: https://support.google.com/googleplay/android-developer/table/4419860
[2]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/res/

# Dev Setup

To develop using Android Studio, install Android Studio and the Android SDK/NDK, then set persistent environment variables. Use the IDE SDK Manager for GUI installs or the command line for scripted installs.

1. Install Android Studio

   - Download and install from https://developer.android.com/studio.
   - On first run open Tools → SDK Manager:
     - Under SDK Platform, install an Android SDK Platform (e.g. Android 36).
     - Under SDK Tools, install
       - Android SDK Build-Tools
       - Android SDK Platform-Tools
       - Android SDK Command-line Tools.
       - “NDK (Side by side)” and a specific NDK version required by the project (or use the same version referenced in the Dockerfile).
   - (Optional) Install CMake if prompted.

2. Install or update SDK/NDK from the command line (optional)

   - Use the SDK command-line tools (sdkmanager). Example:
     ```
     sdkmanager "platform-tools" "build-tools;33.0.2" "platforms;android-33" "ndk;25.2.9519653" "cmdline-tools;latest"
     ```
   - Replace versions with the ones your build requires.

3. Set persistent environment variables

   - Windows (use System → Advanced system settings → Environment Variables or run as Administrator):
     - Recommended: open “Edit the system environment variables” UI and add/modify variables.
     - `ANDROID_HOME=C:\Users\User\AppData\Local\Android\Sdk`
     - `NDK_VERSION=29.0.14206865`

4. Install Go

   - `winget install GoLang.Go`

5. Verify installation
   ```
   $env:ANDROID_HOME
   go version
   java -version         # must be Java 21
   ```

Notes

- Use the exact SDK/NDK versions required by the project (see docker/Dockerfile or project docs).
- Prefer setting ANDROID_SDK_ROOT (or ANDROID_HOME as a legacy alias) and ANDROID_NDK_HOME so build tools and Gradle can find the SDK/NDK.
- On Windows prefer the Environment Variables UI to avoid PATH truncation issues with setx.
- After setting these variables, Android Studio and command-line builds (./gradlew assembleDebug, ./gradlew buildNative) should find the SDK/NDK automatically.

# Building

These dependencies and instructions are necessary for building from the command
line. If you build using Docker or Android Studio, you don't need to set up and
follow them separately.

## Dependencies

1. Android SDK and NDK

   1. Download SDK command line tools from https://developer.android.com/studio#command-line-tools-only.
   2. Unpack the downloaded archive to an empty folder. This path is going
      to become your `ANDROID_HOME` folder.
   3. Inside the unpacked `cmdline-tools` folder, create yet another folder
      called `latest`, then move everything else inside it, so that the final
      folder hierarchy looks as follows.
      ```
      cmdline-tools/latest/bin
      cmdline-tools/latest/lib
      cmdline-tools/latest/source.properties
      cmdline-tools/latest/NOTICE.txt
      ```
   4. Navigate inside `cmdline-tools/latest/bin`, then execute

      ```
      ./sdkmanager "platform-tools" "build-tools;<version>" "platforms;android-<version>" "extras;android;m2repository" "ndk;<version>"
      ```

      The required tools and NDK will be downloaded automatically.

      **NOTE:** You should check [Dockerfile](docker/Dockerfile) for the
      specific version numbers to insert in the command above.

2. Go (see https://docs.syncthing.net/dev/building#prerequisites for the
   required version)
3. Java version 11 (if not present in `$PATH`, you might need to set
   `$JAVA_HOME` accordingly)
4. Python version 3

## Build instructions

1. Clone the project with
   ```
   git clone https://github.com/syncthing/syncthing-android.git --recursive
   ```
   Alternatively, if already present on the disk, run
   ```
   git submodule init && git submodule update
   ```
   in the project folder.
2. Make sure that the `ANDROID_HOME` environment variable is set to the path
   containing the Android SDK (see [Dependecies](#dependencies)).
3. Navigate inside `syncthing-android`, then build the APK file with
   ```
   ./gradlew buildNative
   ./gradlew assembleDebug
   ```
4. Once completed, `app-debug.apk` will be present inside `app/build/outputs/apk/debug`.

**NOTE:** On Windows, you must use the Command Prompt (and not PowerShell) to
compile. When doing so, in the commands replace all forward slashes `/` with
backslashes `\`.

# License

The project is licensed under the [MPLv2](LICENSE).
