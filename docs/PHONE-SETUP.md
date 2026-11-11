# AAFin — phone setup

**Five steps.** Do them once on each phone. The same checklist is in the app: **Settings → Open setup checklist**.

---

## Step 1: Install AAFin

- Install the AAFin APK on the phone (`adb install -r app-release.apk`, or open the APK file on the phone).
- **To update later:** install the newer APK over the top. Accounts and downloads are kept.

## Step 2: Allow unknown sources in Android Auto

1. Open **Android Auto's settings** on the phone.
   - Quickest way: in AAFin's checklist tap **Open Android Auto app info**, then **Additional settings in the app**.
   - Or: phone **Settings → Connected devices → Connection preferences → Android Auto**.
2. Scroll to the bottom and tap **Version** ten times. Accept the prompt to turn on developer settings.
3. Open the **three-dot menu → Developer settings**.
4. Switch on **Unknown sources**.

You only do this **once per phone**. (Already done on the owner's Pixel; still needed on the second phone.)

## Step 3: Show AAFin in the car launcher

- Phone **Settings → Apps → Android Auto → Customise launcher**.
- Make sure **AAFin** is ticked.

## Step 4: Sign in to Jellyfin

1. Open **AAFin** on the phone.
2. Type the **server address** (no need for `https://`) and tap **Find server**.
3. Tap **Sign in with Quick Connect**. AAFin shows a **six-digit code**.
4. On a device already signed in to Jellyfin: **Settings → Quick Connect**, type the code.
5. AAFin signs in by itself within a few seconds.

- **No Quick Connect?** Tap **Use username and password** instead.
- **More than one person or server?** Add each account; switch with the **account button** at the top right.

## Step 5: Connect to the car

- Connect the phone to the car (**cable or wireless**).
- Open **AAFin** from the Android Auto launcher on the car screen.
- Films and episodes **play on the car screen, not on the phone**.

---

## Downloads (optional, for journeys with no signal)

1. In AAFin on the phone: **Browse** → pick a film or episode → **Download**.
2. Choose **720p** (pre-selected, smaller, plays on any phone) or **Original** (the untouched file).
3. Allow **notifications** when asked — that is the download progress.
4. Watch progress in the **Downloads** tab. Downloads then appear under **Downloads** on the car screen.

| Good to know | |
|---|---|
| **Keep AAFin open at first** | If the phone restarts or AAFin is closed for a long time, open AAFin again and unfinished downloads carry on. |
| **Original greyed out?** | The Jellyfin account needs **Allow media downloads** (server Dashboard → Users). 720p always works. |
| **A download says Failed** | Tap **Retry** in the Downloads tab. |

## Per-car picture quality

- Each car appears in the **Cars** tab the first time AAFin runs on its screen.
- There you can **rename** it and set its **quality** (default 720p · 4 Mbps).
