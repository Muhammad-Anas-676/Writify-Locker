# Writify App Locker (Kotlin)

Decoy-PIN app locker + file vault, wrapped around the existing Writify notes app.

## How it works

- **App name/icon on home screen:** "Writify" — looks like a normal notes app.
- **Enter the FAKE pin** → opens Writify (the bundled `index.html`) in a WebView, exactly like the standalone web app.
- **Enter the REAL pin** → opens the Dashboard:
  - **Locked Apps tab** — pick any installed app; once locked, opening it anywhere on the phone shows a full-screen PIN overlay first.
  - **File Vault tab** — import any file. You're asked Move (deletes original, best-effort) or Copy. Vaulted files live in the app's private storage (`filesDir/vault`), which normal file managers and the gallery cannot see or index.
- First launch asks you to set up both PINs (they must differ).

## One manual step after installing

Android requires the user to manually turn on Accessibility permission for any app-locking feature — this cannot be automated for security reasons. After installing, the Dashboard will prompt you to open Settings and enable "Writify" under Accessibility. Without this, app locking won't trigger (the File Vault and PIN screens work regardless).

## Building the APK

Push this folder to a GitHub repo, the included workflow (`.github/workflows/build-apk.yml`) builds a debug APK automatically and uploads it as a build artifact — download it from the Actions tab after the run finishes.

## Replacing the icon

Placeholder icon is at:
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_background.xml`

Swap these (or add your own PNG/adaptive icon set) via GitHub as planned.

## Known limitations (be aware)

- **Accessibility-based locking can be bypassed** by a technically savvy person who disables the service in Settings — there's no way around this on unrooted Android; it's the same limitation every non-root app locker has.
- **"Move" delete of the original file** only succeeds if the picker's content provider grants delete permission. Some providers (e.g. certain cloud-backed pickers) may refuse; the file still gets copied into the vault either way, and you'll get a toast if the original couldn't be removed.
- **QUERY_ALL_PACKAGES** is a restricted permission on the Play Store (fine for sideloading/personal use, would need a Play Store declaration if ever published there).
