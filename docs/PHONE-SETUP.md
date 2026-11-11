# AAFin — phone setup

**Five steps.** Do them once on each phone. The same checklist is in the app: **Settings → Open setup checklist**.

---

## Step 1: Install AAFin from a computer

**Android Auto only lists apps installed by Google Play.** Its "Unknown sources" setting doesn't cover apps like AAFin (Car App Library apps). Installing over USB with `-i com.android.vending` records Google Play as the installer, so Android Auto lists it.

1. Download **`AAFin-vX.Y.apk`** from the [latest release](https://github.com/BBBannon/AAFin/releases/latest) **on the computer**.
2. Phone: **Settings → About phone →** tap **Build number** 7 times → **Developer options → USB debugging** on.
3. Plug the phone in, allow the computer when asked, then run:
   ```
   adb install -r -i com.android.vending AAFin.apk
   ```

- **Don't open the APK on the phone.** It installs, but Android Auto won't list it. Fix it by running the command above.
- **Updates:** AAFin tells you when a new version is out. Install it with the **same command**. Accounts and downloads are kept.

## Step 2: Sign in to Jellyfin

1. Open **AAFin** on the phone.
2. Type the **server address** (no need for `https://`) and tap **Find server**.
3. Tap **Sign in with Quick Connect**. AAFin shows a **six-digit code**.
4. On a device already signed in to Jellyfin: **Settings → Quick Connect**, type the code.
5. AAFin signs in by itself within a few seconds.

- **No Quick Connect?** Tap **Use username and password** instead.
- **More than one person or server?** Add each account; switch with the **account button** at the top right.

## Step 3: Connect to the car once

- Connect the phone to the car (**cable or wireless**).
- **Android Auto only lists AAFin while a car is connected.** Unplugged, it's missing from Customise launcher. That's normal.

## Step 4: Show AAFin in the car launcher

- **While connected to the car:** phone **Settings → Apps → Android Auto → Customise launcher** → make sure **AAFin** is ticked.
- **Not there even while connected?** It was installed on the phone rather than from a computer. Run the Step 1 command, then reconnect.
- **No car handy?** Google's **Desktop Head Unit** on a computer counts as a car. See [`DHU-TEST-PLAN.md`](DHU-TEST-PLAN.md).

## Step 5: Open AAFin on the car screen

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
- There you can **rename** it and set its **quality**. The default is **Auto**: it follows your connection and the car's screen, and lowers itself if the video starts to stall.
