# Backlog

Unprioritized feature ideas, not yet scheduled.

- **App lock on open** — require a code/PIN every time the app is opened.
- **Voice / video calling.**
- **Distress code** — a code that wipes all data/messages when entered.
- **Multiple pairing + chat list** — DONE 2026-11-12 (commit `175290f`). Pair with more than one other person. Jason's design (2026-11-12): the home
  screen is a list of chats (Eric, Joe, Hermes...); tap one to open that conversation.
  - Existing Eric history and Signal session must carry over with no re-pairing, and Eric's phone (one contact) must
    keep working across the upgrade.
  - Notifications name the contact but never show message text.
  - Check the relay: it's configured for two people today.
- **One app for everyone; Hermes is opt-in** — app side DONE 2026-11-12: no Hermes settings in the build;
  setup is a one-time setup code (see docs/hermes-setup-code.md). Remaining: Kenneth's gateway must issue setup
  codes and return the ntfy topic from `/pair` before anyone else can set up Hermes. No longer blocks a store
  release.
- **Server vs. local mode** — let two people choose server-relay or fully local/direct connection.
- **iOS port** — so Bill (a third person, in Texas, on iPhone) can use it. Researched 2026-11-07; nothing started.
  - **Depends on "Multiple pairing" above:** Jason's app pairs with only one person (Eric) today, and the relay is
    configured for exactly two people. Bill joins via an invite code and pairs with Jason by voice code over a call
    (QR needs them in the same room).
  - **App:** a new Swift/SwiftUI app; the Android code doesn't carry over. Signal's official Swift library
    (LibSignalClient) keeps messages end-to-end compatible with the Android app. Building it needs Rust, so prove that
    first.
  - **Relay:** iPhones can't hold a background connection the way RelayConnectionService does, so the relay must send
    Apple push notifications (APNs) to wake the app. The notification only says "New message", never the content.
    Jason and Eric use the relay daily, so test relay changes before deploying.
  - **Build:** no Mac here. Plan: GitHub Actions macOS runners (driven from Windows via gh) build, run the simulator,
    and upload to TestFlight; set the Actions spending limit to $0. Codemagic's free tier is the overflow. The MacinCloud
    $25/month plan was rejected: RDP only, no SSH or admin, so it can't be scripted.
  - **Apple:** Developer Program, $99/year, enrolled as jason@spritzlive.net. Then an App Store Connect API key for
    TestFlight uploads.
  - **Testing:** Bill installs TestFlight and is invited by email. Builds expire after 90 days, so rebuild at least
    quarterly. Real-iPhone testing is Bill's.
  - **Order:**
    1. Apple sign-up.
    2. A CI build proving LibSignalClient compiles for iOS.
    3. A minimal app that redeems an invite, pairs, and exchanges one message with Android.
    4. Push notifications.
    5. Photos and receipts.
- **Hermes: reconnect without opening the app** — found while testing the Hermes agent link, 2026-11-11.
  - **Auto-start after a reboot or app update.** Today both connections (Eric's and Hermes) only start when the app
    is opened, so messages wait until then. Start each connection on boot and after an update, but only if it's
    paired, so there's never a stray "connected" notification.
  - **Reconnect as soon as the network returns.** Listen for Android's network-available signal and reconnect Hermes
    and ntfy right away, instead of waiting out the retry timer (up to 60s).
  - **Retry timers stall in deep sleep.** With the screen off, a 60s retry can stretch to minutes. Affects the Eric
    connection too.
  - **ntfy misses pings sent while it's reconnecting.** Subscribe with `since` so they're picked up.
  - Before testing: `adb logcat -G 16M`, since the phone's log buffer rotates in minutes.
