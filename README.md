# TwoStep

A small Android authenticator app: it makes the 6-digit two-factor codes that GitHub,
Google, Microsoft and other sites ask for, from the QR code each site shows when you turn
on 2FA. It's written in Kotlin (Jetpack Compose) and has no account, no cloud, and no ads.

## What it does

- **Scan a site's 2FA QR code**, or type in its setup key if you can't scan.
- **Codes follow the standard** (TOTP, RFC 6238). The defaults are SHA1, 6 digits and a new
  code every 30 seconds, which is what GitHub uses. SHA256/SHA512, 7-8 digits and other
  periods are supported too.
- **Tap a code to copy it.** The clipboard entry is marked sensitive, so Android 13+ hides it.
- **Keys are encrypted on the phone** with AES-256-GCM, using a key that lives inside the
  Android Keystore. Every save is written atomically and then read back before TwoStep says
  "Saved". GitHub's 28-day check exists because some apps failed to save the key.
- **Encrypted backups:** export one file locked with your password (PBKDF2-HMAC-SHA256 +
  AES-256-GCM), keep it off the phone, and import it on a new phone. Plain `otpauth://`
  lists can be imported too.
- **App lock** (fingerprint or screen lock) and **screenshot blocking** (on by default).
- **Clock check:** compares the phone's clock with GitHub's and warns if codes would be rejected.
- **Updates itself:** on launch it reads the newest release's `latest.json`. When a newer
  build is out, tap **Update now**: it downloads the APK, checks its SHA-256 and opens the
  installer. Android allows that only for an APK signed with the same key, so your accounts
  carry over.

Internet is used for update checks only. Codes are made on the phone and work offline.

## Install

Download `TwoStep.apk` from the [latest release](https://github.com/iknalos/TwoStep/releases/latest)
on the phone and open it. Android asks once to allow installs from your browser. After that,
TwoStep updates itself.

## Turning on GitHub 2FA with it

1. On the laptop: github.com > Settings > Password and authentication > **Enable two-factor authentication**.
2. On the phone: TwoStep > **Add account > Scan QR code**, then **Save**.
3. Type the 6-digit code from TwoStep into GitHub.
4. **Download GitHub's recovery codes** and keep them somewhere other than the phone.
5. Optional: add a passkey on the laptop (Windows Hello), so day-to-day sign-in is just your PIN or fingerprint.

`git push`, the `gh` CLI and the API keep using tokens. They never ask for a 2FA code.

## How it's built and tested

Everything builds in GitHub Actions (`.github/workflows/build.yml`):

- **Unit tests:** the RFC 6238 and RFC 4226 test vectors for all three algorithms, RFC 4648
  base32 vectors, GitHub's QR format, and backup encryption (known-answer PBKDF2, wrong
  password, tampering).
- **Emulator tests (Android 14):** real Keystore round trips, a corrupt vault and a missing
  key are reported as unreadable (never silently replaced), and an **update in place**: an old
  build with accounts gets a newer build installed over it, and every account is still there.
- **Release:** signed with the TwoStep key. The key is kept in Actions secrets, not in this
  repo, and CI refuses to publish an APK signed with any other key. The release carries
  `latest.json` for the updater.

## Layout

```
app/src/main/java/com/iknalos/twostep/
  otp/      Base32, Totp (RFC 6238/4226), OtpAuthUri (QR code format)
  data/     Account + JSON codec, Vault (Keystore-encrypted file), BackupCrypto, AppSettings
  update/   Updater (latest.json, download + SHA-256 check, system installer)
  ui/       Compose screens
  AppModel.kt, MainActivity.kt
```
