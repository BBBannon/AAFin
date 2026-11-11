# AAFin — Desktop Head Unit (DHU) test plan

**Goal:** prove the car app on a real Android Auto host before it goes near the car.
**Time:** about 20 minutes. **Stop at the first FAIL in section 1 or 3** — those are the core.

What is already proven without a head unit (see `ARCHITECTURE.md` §11.4): the video window draws, letterboxes, survives surface changes and keeps drawing with the phone asleep (Android 14 emulator).
What only this plan can prove: **Android Auto hands AAFin a surface, shows the templates, and counts them the way the docs say.**

---

## 0. Set-up

| # | Do | Expect |
|---|---|---|
| 0.1 | Phone on USB, unlocked. `C:\Users\Ben\Android\sdk\platform-tools\adb.exe devices` | Phone listed as `device` (not `offline` / `unauthorized`) |
| 0.2 | `adb install -r app\build\outputs\apk\release\app-release.apk` | `Success`. **Release build only** — the debug build has a different signature |
| 0.3 | Phone: Android Auto settings → tap **Version** 10× → ⋮ → **Developer settings** → tick **Unknown sources** | Ticked |
| 0.4 | Phone: Android Auto settings → ⋮ → **Start head unit server** | Notification "Head unit server running" |
| 0.5 | PC: `adb forward tcp:5277 tcp:5277` | Prints `5277` |
| 0.6 | PC: `C:\Users\Ben\Android\sdk\extras\google\auto\desktop-head-unit.exe` | DHU window opens showing Android Auto |
| 0.7 | Second terminal: `adb logcat -c` then `adb logcat -s AAFin/Car AAFin/Surface AAFin/Playback AAFin.Playback AndroidRuntime:E` | Quiet until AAFin opens. **Leave it running** |

- **AAFin missing from the DHU launcher?** Phone: Android Auto settings → **Customise launcher** → tick AAFin. Still missing → 0.3 was not applied; restart Android Auto.
- Sign in to Jellyfin on the phone first **only if Stage 3 is installed**. Without it, do sections 1–2 (signed-out path and surface) and stop.

---

## 1. Launch and signed-out state

| # | Do | Expect on the DHU | Expect in logcat |
|---|---|---|---|
| 1.1 | Open **AAFin** from the launcher | A list or message appears within ~2 s. No Android Auto error screen | `AAFin/Car: Session created (car API level N)` |
| 1.2 | — | Behind/beside the card: dark background with the **AAFin logo** | `AAFin/Surface: Surface available: WxH @Ddpi` then `Presentation showing on virtual display N` |
| 1.3 | — | — | `AAFin/Car: Car profile: key=… name=… quality=P720_4M` within ~5 s. **Note the key** (`model:…` or `screen:…`) |
| 1.4 | If signed out | Message **"Sign in on your phone first."** with **Retry** and **Downloads** | `Home: active account none` |
| 1.5 | Tap **Retry** | Same message, no error | — |
| 1.6 | Tap **Downloads** | "Downloads" list (or "No downloads yet…"). Back returns | — |

**FAIL:** **No logo / black surface** → note the `AAFin/Surface` lines. `The car would not give AAFin a surface` = permission problem; `Could not show the video window` = window problem. Stop here and report.
**FAIL:** **`Surface available` never appears** → Android Auto is not giving a navigation surface. Stop and report.

---

## 2. Browse (needs a signed-in account)

| # | Do | Expect |
|---|---|---|
| 2.1 | Home | Rows: **Continue watching, Next up, your libraries, Downloads, Search** (+ **Switch account** only with 2+ accounts). If they do not all fit: a **More…** row. A search icon top-right |
| 2.2 | Tap a **film library** | Grid of posters (placeholders first, posters fill in within a few seconds, **without the grid jumping**). Title like "Films · 1–5 of 300" when there are more |
| 2.3 | Tap **More…** (if shown) | Next page. **Back** returns to the previous page |
| 2.4 | Keep tapping More… | After at most two more pages there is **no More…** — by design. No Android Auto error |
| 2.5 | Back to Home → a **TV library** → a series | Episodes in sections per season, **or** a list of seasons (long series). Rows read "S1 E3 · Title", second line "Watched" / "12 min left" |
| 2.6 | Long series: tap a season | That season's episodes, starting at the next one to watch |
| 2.7 | Home → **Search** → type part of a title | Results appear about half a second after you stop typing. Tap a series → its episodes |
| 2.8 | Home → **Continue watching** / **Next up** | Lists with posters, or "Nothing in progress." |
| 2.9 | Phone: aeroplane mode on. DHU: open a library | Message **"Can't reach the server. Check your connection and try again."** with **Retry** |
| 2.10 | Aeroplane mode off → **Retry** | The library loads |
| 2.11 | Wander deep: Home → TV → More… → series → Back → Back → another series… for a minute | **Never** an Android Auto "step limit" error. If logcat shows `Template budget used up … restarting from Home`, you land on Home — that is the safety net, note what you tapped |

**FAIL:** **Android Auto error mentioning steps / templates / "too many"** → the host counts differently from the docs. Report the exact taps and the last `AAFin/Car` lines.

---

## 3. Playback — the critical part

| # | Do | Expect on the DHU | Expect in logcat |
|---|---|---|---|
| 3.1 | Tap a film | Card disappears. "Loading…" on the picture, then **video with sound** | `Open player: MOVIE …`, `Play MOVIE … at P720_4M from N s` |
| 3.2 | Look at the picture | **Correct shape** — black bars, not stretched. Title + progress bar + clock at the bottom for ~4 s, then they fade | `Visible area: Rect(…)` lines as the strips show/hide |
| 3.3 | Look at the buttons | Top strip: **Back, −10 s, Pause, +30 s**. Side: **Options** (sliders icon), maybe a pan button | — |
| 3.4 | Wait for the strips to hide | Picture grows slightly to fill the space; nothing is cut off | `Visible area` changes |
| 3.5 | **Click the picture** once | Overlay (and the strips) come back | `Tap: show overlay` |
| 3.6 | Click again while the overlay shows | **Pauses**; button becomes Play; overlay stays | `Tap: play/pause` |
| 3.7 | Click again | Resumes | `Tap: play/pause` |
| 3.8 | Tap **+30 s**, then **−10 s** | Position jumps; overlay shows the new time | — |
| 3.9 | **Drag sideways** on the picture | "+0:45"-style preview, then a seek. **Dragging right must go forwards** | `Drag: seek N s` |
| 3.10 | **Lock the phone** (screen off) for 30 s | **Picture and overlay keep updating** | no new errors |
| 3.11 | Tap **Options → Subtitles** → pick one | Back at Options; subtitles appear on the picture, readable size | `Option chosen: SUBTITLES #n` |
| 3.12 | Options → Subtitles → **Off** | Subtitles gone | — |
| 3.13 | Options → **Audio** → another track | Sound changes (the stream may restart at the same place) | `Option chosen: AUDIO #n` |
| 3.14 | Options → **Quality** → 480p · 2 Mbps | Picture restarts at the same position, softer | `Car profile <key>: quality set to P480_2M` |
| 3.15 | **Back** (top strip) | Returns to the grid; sound stops; logo is back | `Player closed; stopping playback` |
| 3.16 | Play another film | Starts at **480p** (the car's profile remembered it) | `Play … at P480_2M …` |
| 3.17 | Jellyfin web → dashboard → activity | The first film shows the position you stopped at | — |

**FAIL:** **Sound but no picture (logo or black)** → change `app:surface_type` to `texture_view` in `app/src/main/res/layout/car_presentation.xml`, rebuild, retest, report which worked.
**FAIL:** **Picture stretched** → report the `Surface available` size and a screenshot.
**FAIL:** **3.5 does nothing** (no `Tap:` line) → the host is not passing clicks. The buttons still work; report the car API level from 1.1.
**FAIL:** **3.9 goes the wrong way** → known risk; one-line fix (`ARCHITECTURE.md` §11.9 item 4).
**FAIL:** **3.10 freezes** → report the phone's Android version.

---

## 4. Episodes and auto-play

| # | Do | Expect | Logcat |
|---|---|---|---|
| 4.1 | Play an episode that is **not the last**; +30 s to near the end; let it finish | "Up next in 8 s · S1 E4 · …" counts down, then the next episode plays | `Finished … ; next is …` |
| 4.2 | During the countdown tap **−10 s** | Countdown cancelled; the episode carries on | — |
| 4.3 | Let the **last** episode (or a film) finish | Player closes back to the list | `Finished … ; nothing to play next` |

---

## 5. Downloads and offline (needs a finished download from Stage 3)

| # | Do | Expect |
|---|---|---|
| 5.1 | Phone: aeroplane mode on. DHU: Home | Message with **Downloads** button (or the menu if it was already loaded) |
| 5.2 | **Downloads** | The finished downloads, with posters |
| 5.3 | Tap one | Plays with no network. Options → Quality says **"Downloaded copy"** |

---

## 6. Accounts (needs two signed-in accounts)

| # | Do | Expect |
|---|---|---|
| 6.1 | Home → **Switch account** | Both accounts, the current one ticked |
| 6.2 | Pick the other | Back at Home showing the other account's libraries. A brief flash of an empty screen can happen (the budget restart) — fine |

---

## 7. Disconnect and reconnect

| # | Do | Expect | Logcat |
|---|---|---|---|
| 7.1 | While a film plays, **close the DHU window** | Sound stops on the phone within a second or two | `Session destroyed; stopping playback`, `Renderer released`, `Virtual display released` |
| 7.2 | — | No crash | no `AndroidRuntime` lines |
| 7.3 | Start the DHU again, open AAFin | Home, logo. **Same car profile key** as 1.3 | `Car profile: key=<same>` |
| 7.4 | **Resize** the DHU window if it allows, or restart it with a different screen config | Picture re-fits, no crash | `Surface destroyed` / `Surface available` with the new size |
| 7.5 | Open Google Maps in the DHU, then AAFin again | Maps is not disturbed (AAFin never starts "navigation") | — |

---

## 8. What to send back if anything fails

1. The step number.
2. `adb logcat -d -s AAFin/Car AAFin/Surface AAFin/Playback AAFin.Playback AndroidRuntime:E > aafin-log.txt`
3. A screenshot of the DHU window.

The log never contains tokens, stream URLs or search text, so it is safe to share.

---

## 9. In the real car (after the DHU passes)

- Repeat 1.1–1.3, 3.1–3.10 and 7.1 in **each** car, **wired and wireless**.
- Check 1.3's key: the Duster should give the **same key on every journey**. A `model:` key one day and a `screen:` key the next means the car reports its model unreliably (`ARCHITECTURE.md` §11.9 item 5).
- While moving (passenger operating): lists shorten to about six rows — expected.
