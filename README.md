# X Linux (touch UI + real Parrot OS)
The phone-style launcher UI lives in `app/src/main/assets/index.html` (runs in a WebView) and talks to the real Parrot OS runtime through the `XL` bridge (Bridge.kt).
Real Parrot OS (official parrotsec/core image, Debian 13 based) on Android, rootless, no Termux. Kotlin + PRoot.

## Build the APK (no PC needed)
1. Create a GitHub repo, upload this folder's contents, open **Actions → build-apk → Run workflow**.
2. Download artifact **XLinux-debug-apk**, install it on the phone (allow unknown sources).

## Use
Install Linux (needs internet once) → Self-test → type real commands (`apt update`, `apt install -y python3`, `python3 --version`).

## Status: UNTESTED scaffold
- The PRoot binaries come from the Termux repo (scripts/fetch-proot.sh); if the first CI run or first launch fails, send me the log.
- Shell is pipe-based (no PTY): full-screen apps like nano/vim won't work yet. Next step: PTY terminal.
- Tested on: nothing yet. Fill in docs/compatibility.md after trying OPPO A15.
License: GPLv3 recommended.

## Notes
- First install downloads roughly 200-400 MB and needs about 2 GB free. Each image layer is checked against its SHA-256 digest.
- Security tools: `apt install parrot-tools-full` is huge; install single tools instead (e.g. `apt install nmap`).
- Parrot OS is a trademark of Parrot Security; X Linux is an independent project and not affiliated with it. Use security tools only on systems you own or are authorized to test.

## Cornerstone
`app/src/main/assets/cornerstone.txt` holds the author credits. Its SHA-256 is compiled into `Integrity.kt`.
Changing the file stops the build (Gradle task `verifyCornerstone`) and, at run time, stops the app and shows a lock screen.
It never deletes user files. It is a deterrent only: open-source code can always be rebuilt without the check.
