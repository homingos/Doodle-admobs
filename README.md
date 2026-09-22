# Doodlepop — Photo filter Android app with AdMob

A minimal Kotlin Android app that picks a photo, applies one of three filters
(**Sketch**, **B&W**, **Bubble**), saves to the gallery, and monetizes via
**banner + interstitial** AdMob ads — wired up to pass Google's policy review.

> Doodlepop = doodle (sketch) + pop (bubble), the two signature effects.

## What's in the box

- 3 filters in pure-Kotlin (`ImageFilters.kt`) — no OpenCV, ~10 MB APK
- Material 3 UI with toolbar + bottom-anchored banner
- **AdMob banner** (always-on at the bottom)
- **AdMob interstitial** on Save, with 60-second cooldown
- **UMP / GDPR consent** gathered before any ad loads
- **About / Privacy** screen with policy link and "Reset Ad Consent" action
- Debug builds use Google's universal **test** ad IDs; release builds use your real ones

## Build & run

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Or open in Android Studio → Run.

Saved images land in `Pictures/Doodlepop/` on the device.

---

## ✅ Pre-launch checklist for AdMob account approval

Google reviews new AdMob publishers when their first app starts serving ads.
These are the items they actually check. Everything marked **✓ done** is wired
in code; everything marked **TODO** needs an action from you outside the IDE.

### In the app (already done)

- **✓ UMP consent gathered before `MobileAds.initialize`** — `ConsentManager.kt` runs first, ads load only after `canRequestAds()` returns true.
- **✓ Reset-consent control** — required by Google so users can change their mind. Lives in *About → Reset Ad Consent*.
- **✓ Test IDs in debug, real IDs in release** — automatic via `buildConfigField` in `app/build.gradle.kts`. Clicking ads in debug is safe; clicking your own production ads will get the account banned.
- **✓ Interstitial at a natural break point** — fires on Save, never on app launch, with a 60-second cooldown. Loading ads during app launch or on every action is the #1 cause of policy rejection.
- **✓ Banner doesn't overlap content** — image preview is constrained above the banner.
- **✓ App ID in `AndroidManifest.xml`** — `ca-app-pub-7343868790309663~1386077355`.
- **✓ Interstitial unit ID in `app/build.gradle.kts` (release)** — `ca-app-pub-7343868790309663/2507587335`.

### Before you upload to Play Console

1. **Generate a privacy policy** (free):
   - <https://app-privacy-policy-generator.firebaseapp.com/> → tick "AdMob" and "Google Analytics for Firebase" as third-party services.
   - Host it publicly. Easiest free option: paste into a Notion page (Share → Publish to web) or a GitHub Pages repo.
   - **Update** `app/src/main/res/values/strings.xml` → `privacy_policy_url` with that URL.
2. **Create a real AdMob banner unit** (you only have an interstitial right now):
   - <https://apps.admob.com/> → your app → *Ad units → Add ad unit → Banner*.
   - Replace `admob_banner_unit_id` in `app/src/main/res/values/strings.xml` with the unit ID it gives you (the one with a `/` not `~`).
3. **Pick a unique applicationId**. `com.doodlepop.app` is fine if it isn't taken on Play; otherwise change it in `app/build.gradle.kts` to `com.<yourbrand>.doodlepop`.
4. **Replace the launcher icon**. The default vector icon is functional but not distinctive — use Android Studio's *File → New → Image Asset* to generate a branded one.
5. **Sign the release build** — see [Signing](#signing-for-release) below.

### In Play Console

1. **Create the app**: <https://play.google.com/console> → Create app. Category: *Photography*. Free.
2. **Upload `app-release.aab`** under Release → Production → Create new release.
3. **Data Safety form** (under App content):
   - Personal info: **None collected**.
   - Device or other IDs: **Collected, shared** → reason: Advertising or marketing. (AdMob uses GAID.)
   - Approximate location: **Collected, shared** → reason: Advertising or marketing.
   - Mark data as **collected via third-party SDK** (Google AdMob).
   - Encryption in transit: **Yes**.
4. **Privacy policy URL**: paste the URL from step 1 above.
5. **Content rating**: take the IARC questionnaire → answer honestly (it's a photo editor → "Everyone"). Be sure to mark that the app displays ads.
6. **Target audience and content**: age 13+ (AdMob ToS minimum).
7. **Ads declaration**: tick "Yes, my app contains ads."
8. **Link your AdMob account** in Play Console → *Monetize with AdMob → Link*.
9. **App access**: if anything needs login (Doodlepop doesn't), declare it. If not, mark "All functionality is available without restrictions."
10. **Submit for review**. First review takes 1-7 days. After approval, you can promote to Production.

### In AdMob console (post-Play-publish)

1. **Verify the app**: AdMob auto-links to the Play listing once both use the same package name.
2. **Add `app-ads.txt`** to your website root (e.g. `https://yourbrand.com/app-ads.txt`):
   - AdMob shows you the exact line to add under *Apps → app-ads.txt → Set up*.
   - Add the same domain to your Play Listing under *Developer page → Website*.
   - AdMob crawls this within 24 hours; verified = improved fill rate.
3. **Payment & tax info** — *Payments → Settings*. Required before Google releases your earnings (threshold is $100).

### What gets accounts banned

- **Clicking your own live ads.** Always test with debug builds (test IDs). After publishing, only check that ads load — never click them on a device tied to your AdMob account.
- **Putting real unit IDs in debug builds.** Other people's clicks on debug-channel ads count as invalid traffic.
- **Showing ads in screens with no user-generated content.** Doodlepop only shows ads after the user is interacting with a photo — compliant.
- **Stacking ads.** One banner + one interstitial-on-save is fine. Two banners on one screen is not.
- **Modifying ad views.** Don't wrap the AdView in a container that obscures or resizes the ad creative.

---

## Switching to release IDs

Already wired. `app/build.gradle.kts` selects:

| Build type | Interstitial unit ID | Banner unit ID |
|---|---|---|
| `debug` | Google test (`/1033173712`) | Google test (`/6300978111`) |
| `release` | Your real (`/2507587335`) | Test until you create one — TODO above |

To swap the banner once you create it, change `admob_banner_unit_id` in
`strings.xml` (it's read identically in debug and release; if you want to keep
testing in debug, move it to a `buildConfigField` like the interstitial).

---

## Signing for release

```bash
keytool -genkey -v -keystore release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias doodlepop
```

Create `keystore.properties` next to `app/`:

```
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=doodlepop
keyPassword=...
```

Wire into `app/build.gradle.kts`:

```kotlin
import java.util.Properties
import java.io.FileInputStream

val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(ksProps.getProperty("storeFile") ?: "/dev/null")
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias")
            keyPassword = ksProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

Then build the Play upload bundle:

```bash
./gradlew :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

## Serving the Vertex HTML5 creative through this interstitial

The interstitial slot is wired to AdMob Mediation via a **custom event** —
when AdMob's waterfall routes an impression to our custom event entry, we
load and play Vertex's interactive HTML5 creative inside a WebView instead of
a Google-served ad.

No code changes are needed in the app; everything is configured in the AdMob
console against the production interstitial unit
(`ca-app-pub-7343868790309663/2507587335`).

### One-time AdMob console setup

1. <https://apps.admob.com/> → *Doodlepop* → *Ad units* → click your
   **interstitial** unit (the one whose ID is referenced as
   `ADMOB_INTERSTITIAL_UNIT_ID` in `app/build.gradle.kts` release block).
2. Switch to the **Mediation** tab → *Create mediation group* (or edit the
   existing one) → platform **Android**, ad format **Interstitial**.
3. Under *Ad sources*, click **Add custom event**:
   - **Label**: `Vertex HTML5`
   - **Class Name**: `com.doodlepop.app.VertexCustomEventInterstitial`
   - **Parameter** (must be valid JSON, one line):
     ```
     {"campaign":"5svwQBRlBwdG","workerBase":"https://vertex-player.appless.workers.dev","landing":"https://your-landing-page.example","duration":"00:00:30"}
     ```
   - **eCPM**: set high (e.g. `100`) while testing so Vertex always wins the
     waterfall. Drop this back later if you blend Vertex with AdMob house ads.
4. Keep the default AdMob Network entry in the group at a lower eCPM (e.g.
   `1`) as a fallback so the slot still fills if our worker is unreachable.
5. Save. Mediation config can take **15–60 minutes** to propagate to live
   devices — be patient before deciding it isn't working.

### Testing on-device

- The app's existing `InterstitialAdManager` already calls
  `InterstitialAd.load(unitId)` and `showIfReady(activity, …)` — nothing
  needs to change there. Once the mediation group is live, the same
  load/show calls will route to the Vertex custom event.
- **Debug builds** use Google's universal test interstitial ID
  (`ca-app-pub-3940256099942544/1033173712`) and will NOT pick up the
  mediation config — they always serve the Google test ad. To exercise the
  Vertex path, build a release variant (or temporarily swap the debug
  `ADMOB_INTERSTITIAL_UNIT_ID` to the real one — but **do not click ads** in
  that build; clicking your own live ads gets the AdMob account banned).
- When the interstitial does serve Vertex, you'll see logs tagged
  `VertexCustomEvent` (adapter lifecycle) and `VertexInterstitial`
  (WebView + SIMID handshake) in `adb logcat`.

### What the creative actually loads

`VertexInterstitialActivity` loads `<workerBase>/simid.html` from the Vertex
worker, then drives the SIMID protocol natively from Kotlin
(`createSession` → `init` → `startCreative`). Click-throughs from inside the
creative arrive as `requestNavigate` messages and are opened with an
`ACTION_VIEW` intent; the Activity then finishes so AdMob sees the ad
dismiss.

## Testing the consent flow

UMP only shows the form for users in GDPR regions. To force-test it from a US
device, in AdMob Console: *Privacy & messaging → GDPR → Add test device IDs*
(get yours from logcat: `adb logcat | grep "Use RequestConfiguration"`).

To reset consent locally: open Doodlepop → *About* → *Reset Ad Consent* →
restart the app.

## Project layout

```
app/src/main/
  AndroidManifest.xml                   – Activities, AdMob App ID
  java/com/doodlepop/app/
    MainActivity.kt                     – UI, picker, save, toolbar
    AboutActivity.kt                    – Privacy + reset consent
    ImageFilters.kt                     – Sketch / B&W / Bubble
    InterstitialAdManager.kt            – Load/show/cooldown logic
    ConsentManager.kt                   – UMP GDPR/CCPA flow
    VertexCustomEventInterstitial.kt    – AdMob mediation adapter → Vertex creative
    VertexInterstitialActivity.kt       – Full-screen WebView that hosts simid.html
  res/
    layout/activity_main.xml            – Toolbar + preview + buttons + AdView
    layout/activity_about.xml
    menu/main_menu.xml
    values/strings.xml                  – Banner unit ID + policy URL
    values/themes.xml
```
