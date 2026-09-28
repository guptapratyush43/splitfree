# Split Free

A free Android app for splitting expenses with friends, like Splitwise but with no ads and no paywall.

- Groups with a photo of the place they're named after ("Goa trip" gets a Goa beach)
- Invite by Google email or share a WhatsApp link / QR code
- Add expenses with one or several payers, split **equally** or **unequally**, exact to the paisa
- Only the people in an expense get a notification; comments and reminders too
- Settle up, balances per member, totals with charts, and **simplify group debts**
- Automatic backup to your own Google Drive's hidden app folder

## Download

Grab the latest APK from **[Releases](../../releases/latest)**.

Android may show a Play Protect "unknown app" notice because the app isn't from the Play Store yet. Tap **More details → Install anyway**.

## Privacy

Group data lives in Firebase so every member sees the same balances. No ads, no analytics. See the [privacy policy](https://guptapratyush43.github.io/splitfree/privacy.html).

## How it's built

- **App:** Kotlin + Jetpack Compose, Firebase Auth (Google sign-in), Cloud Firestore, Firebase Cloud Messaging
- **Server:** a Cloudflare Worker (`worker/`) that sends pushes and handles joining, leaving and account deletion
- **Security rules:** `firestore.rules`
- All on free tiers.

## Build

Android Studio's JDK 17, then:

```bash
./gradlew assembleRelease
```

You need your own `app/google-services.json` from a Firebase project, and a signing key in `signing/` (both git-ignored).

## Credits

Avatars: "Big Smile" by Ashley Seo via [DiceBear](https://www.dicebear.com), licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
