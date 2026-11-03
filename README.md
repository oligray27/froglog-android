# FrogLog for Android

A [Capacitor](https://capacitorjs.com) app that wraps [FrogLog](https://froglog.co.uk), so the system bars follow your theme, and adds three home screen widgets (Current Activity, Online Now and Last 7 Days). The site loads from the web, so web changes reach the app without an update. A new release is only needed for native changes.

## Install and update with Obtainium

1. Install [Obtainium](https://obtainium.imranr.dev).
2. Add app, paste this repository's URL, and leave the source as GitHub.
3. Install the APK it finds. Obtainium then tells you when a new release is out.

Releases are named `froglog-<version>.apk` and are all signed with the same key, so each one installs over the last.

## Building

You need the Android SDK, JDK 17 or later (Android Studio's bundled one works) and Node.

```
npm install
cd android
./gradlew assembleLiveDebug     # the live app, signed with your debug key
```

There are two flavours. `live` is the real app, loading https://froglog.co.uk. `dev` is **FrogLog Dev**, which installs beside it and loads a dev server on this PC; `npm run dev-app` builds and installs it.

Push notifications and the widgets' background refreshes need `android/app/google-services.json` from your own Firebase project, for the `live` package name. It is not in this repository. Without it the app builds, but push does nothing.

## Releasing

```
npm run release -- 1.2.0            # build dist/froglog-1.2.0.apk and check its signature
npm run release -- patch --publish  # also commit, tag, push and create the GitHub release
```

It needs the release key. `android/keystore.properties` (untracked) points at a keystore outside the repository. **Back that keystore up.** Android only installs an update signed by the same key as the installed app, so if it is lost everyone has to uninstall and reinstall.

## Layout

- `android/app/src/main/java/uk/co/froglog/app/widgets/`: the widgets (`QuickSessionWidget` is Current Activity, `FriendsWidget` is Online Now, `ThisWeekWidget` is Last 7 Days), the theme picker and the theme table.
- `scripts/`: building, releasing and the widget tools. `generate-widget-themes.js`, `generate-assets.js` and `debug-widget.js` read files from the FrogLog web app's source, so they need that checked out next to this folder (`../frontend`, `../backend`). Their output is committed, so building does not.
- `npm run widget-state -- <state>` puts made-up sessions in the widgets on a connected phone (FrogLog Dev only), to see each look.
