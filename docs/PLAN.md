# homecam — implementation plan

A two-phone home camera system. An old Android phone becomes the camera and the
signaling server; your daily phone is the viewer. No cloud account, no subscription,
no third box.

> **Status:** M0–M9 are implemented. Seven places where the implementation diverged from
> this document — and why — are recorded in [`DECISIONS.md`](DECISIONS.md); this file is
> kept as written so the reasoning can be compared against what was actually built.

The architecture follows AlfredCamera's; we write our own code, our own protobuf schema, and
we use an openly-licensed detection model. None of Alfred's model files, protobuf definitions
or code are reused.

---

## 1. Goals and non-goals

**Goals**

- Live view + two-way audio from camera phone to viewer phone, on LAN and remotely.
- Motion-triggered object detection (person / vehicle / pet / package) with clips
  recorded to the camera phone's own storage.
- **Only devices I have explicitly paired can ever see a feed**, and I can see at a
  glance who those are and every time anyone looked.
- Runs unattended for weeks on a plugged-in phone.

**Non-goals**

- Multiple accounts, sharing, or any notion of "users" beyond paired devices.
- Cloud storage, subscriptions, ads, telemetry.
- Supporting anyone else's hardware.
- Surviving a determined attacker with physical access to an unlocked camera phone.

**Devices**

| Role | Device | OS |
|---|---|---|
| Camera + signaling server | Galaxy S20 Ultra (spare) | Android 13, API 33 |
| Viewer | Galaxy S25 Ultra (SM-S938U) | Android 16, API 36 |

---

## 2. Security and privacy model

This is the pillar the rest of the design hangs off, not a feature bolted on at the end.
Two hard requirements drive it:

> **R1.** No third party can view a feed without my explicit permission.
> **R2.** I can see how many devices have access to each camera, and I can see a log of
> every access, somewhere obvious.

### 2.1 Threat model

| # | Threat | Addressed by |
|---|---|---|
| T1 | Someone else on my LAN (guest Wi-Fi, compromised IoT box) finds the camera via mDNS and connects | §2.2 allowlist, §2.4 opaque advertisement |
| T2 | Someone photographs or intercepts the pairing QR | §2.3 one-time, time-boxed token |
| T3 | Network attacker MITMs the LAN (ARP spoofing) | §2.2 pinned self-signed TLS + §2.5 DTLS fingerprint binding |
| T4 | Media path hijacked even though signaling was authenticated | §2.5 |
| T5 | Anyone else on my tailnet, when accessing remotely | Key allowlist still applies; plus Tailscale ACLs |
| T6 | A malicious app on either phone reads recorded clips | §2.7 app-private storage |
| T7 | Camera phone is lost or stolen | §2.7 Android FBE; remote revoke from viewer |
| T8 | A device that *used* to be trusted (ex-flatmate's phone) | §2.6 visible roster + one-tap revoke |
| T9 | Detection alerts leak what happens in my house to a push provider | §2.8 client-side encrypted push payloads |

### 2.2 Identity: pinned keys, no passwords

Each role generates an **Ed25519 identity keypair on first run**, stored in the Android
Keystore (hardware-backed where available, `StrongBox` when present). The private key
never leaves the device and is never displayed, exported or backed up.

- The camera's WebSocket serves **TLS with a self-signed certificate whose key is bound
  to its identity key**. The viewer pins that certificate at pairing time.
- Every connection thereafter is a **mutual challenge-response** over the two pinned
  public keys. There is no password, so there is nothing to guess, phish, or reuse.
- An unrecognised public key is refused before any application data is exchanged.

### 2.3 Pairing

Pairing is the only moment trust is established, so it is deliberately narrow:

1. Pairing mode must be **explicitly started on the camera device** and requires the
   device to be unlocked.
2. It is **time-boxed to 120 seconds** and **single-use** — one successful pairing ends it.
3. The camera shows a QR containing: its identity public key, its cert fingerprint,
   host/port, and a **one-time random token**.
4. The viewer scans, connects, and proves possession of the token while presenting its
   own public key. The camera adds that key to the allowlist and burns the token.
5. Both devices then display a **short verification phrase** derived from both public
   keys. They must match. This defeats a relay attack during the pairing window.

A leaked QR photo is useless after 120 seconds, and useless immediately if the pairing
already completed.

### 2.4 Discovery leaks nothing

The mDNS advertisement carries a service type, a port, and an **opaque rotating
instance id** — no camera name, no account, no device identifiers. A LAN scanner learns
that some service exists and nothing else. The camera responds meaningfully only to
allowlisted keys.

### 2.5 Binding the media path to the authenticated identity

This is the step that is easy to skip and expensive to get wrong. WebRTC's media
encryption is keyed by DTLS, whose fingerprint travels in the SDP. Authenticating the
signaling channel is therefore only half the job:

> **The DTLS fingerprint in the received SDP must be checked against the fingerprint the
> authenticated peer committed to over the pinned channel. Mismatch = tear down the
> session, log it, and notify.**

Without that check, an attacker who can inject SDP still gets the media even though your
WebSocket was perfectly authenticated.

### 2.6 Visible access roster (R2, part one)

The camera app's home screen shows, above the fold:

```
  2 devices have access        [ Manage ]
```

`Manage` opens a roster listing every paired viewer:

| Field | Example |
|---|---|
| Name | "Ethan's S25" |
| Key fingerprint (short) | `7K2P-QM4X-9DFA` |
| Paired | 2026-08-22 00:41 |
| Last seen | 3 minutes ago |
| Sessions (30d) | 47 |
| — | **[ Revoke ]** |

Revoke is immediate: the key is dropped and any live session from it is torn down
mid-frame. There is also a **Revoke all** that returns the camera to unpaired state.

The same roster is readable from the viewer app over the `ACCESS` service, so you can
audit from your daily phone without walking to the camera.

### 2.7 Access log (R2, part two)

An append-only, **hash-chained** log on the camera (each entry carries a SHA-256 over
the previous entry, so deletion or edit is detectable). Every one of these is an entry:

- session opened / closed, with viewer key, duration and source IP
- clip viewed, with clip id
- clip deleted, settings changed, privacy mask edited
- pairing started / completed / expired, viewer revoked
- **any rejected connection attempt** — wrong key, bad fingerprint, expired token

Surfaced in three places, because a log nobody looks at is not a control:

1. A log screen in **both** apps.
2. A **live indicator on the camera device itself** — while a session is active the
   screen shows `● LIVE — Ethan's S25` and posts an ongoing notification.
3. A **push to every paired viewer when any session starts**. If a device you don't
   recognise starts watching, your phone buzzes.

### 2.8 Recording and alert privacy

- Clips live in **app-private internal/external storage** (`getFilesDir()` /
  `getExternalFilesDir()`), never `MediaStore` and never a shared directory, so other
  apps on the phone cannot read them (T6).
- **No second encryption layer at rest.** Android's file-based encryption already covers
  device theft, and a per-file key stored on the same phone would be security theatre.
  This is a deliberate decision, not an omission.
- **Push alerts carry ciphertext.** The camera encrypts `{event type, timestamp, clip id,
  thumbnail}` to the viewer's public key and posts the ciphertext to a random-topic
  push endpoint. The push provider sees opaque bytes and a topic name, never a thumbnail
  of your living room. Falls back to a **content-free** "check your camera" ping if
  encryption or delivery fails.
- **Privacy masks**: regions blanked *in the encoded frame* before it reaches the encoder,
  so masked areas exist in neither the live stream nor the recording. Distinct from a
  detection zone, which only limits where detection runs.

### 2.9 Egress inventory

The app makes exactly three kinds of outbound connection, all listed on a **Network
activity** screen in the app:

| Destination | When | Content |
|---|---|---|
| The paired viewer | Live session, RPC | E2E via DTLS-SRTP |
| Push endpoint (optional, off by default) | Detection event | Ciphertext only |
| Tailscale (optional) | Remote access | Overlay transport |

No analytics, no crash reporting, no remote config, no ad SDKs. The permission set in
the manifest is kept minimal so it can be audited at a glance.

---

## 3. Architecture

Three planes, following Alfred's split, with the cloud removed:

```
  CAMERA phone (S20U)                                  VIEWER phone (S25U)
 ┌────────────────────────┐                          ┌────────────────────┐
 │ CameraX ─┬─ GL/mask ───┼── H.264 enc ──┐          │                    │
 │          └─ ImageAnalysis                │         │                    │
 │              └─ motion → TFLite          │         │                    │
 │                    │                     │         │                    │
 │              MP4 muxer ◄─────────────────┤         │                    │
 │              (v + a + meta)              │         │                    │
 │                                          ▼         │                    │
 │  WSS server ◄──── mDNS ────────► discovery ───────►│  WSS client        │
 │  (pinned TLS, key allowlist)   auth, SDP, ICE      │                    │
 │                                                    │                    │
 │  ◄════════ WebRTC SRTP: H.264 + Opus ═════════════►│  SurfaceViewRenderer│
 │  ◄════════ DataChannel: RpcMessage ═══════════════►│                    │
 │                                                    │                    │
 │  Room: allowlist │ clip index │ audit log          │                    │
 └────────────────────────┘                          └────────────────────┘
```

**Modules** — two, not ten. The split exists to make the protocol testable without a device:

| Module | Kind | Contents |
|---|---|---|
| `:core` | pure JVM | protobuf types, RPC envelope + dispatcher, crypto/identity, pairing state machine, retention policy. **Unit-testable with no emulator.** |
| `:app` | Android | signaling, WebRTC, capture, detection, recording, audit storage, both UIs |

Split further only if build times hurt. A single APK carries both roles; the role is
chosen at first launch and stored.

---

## 4. Wire protocol

### 4.1 Signaling (WebSocket, TLS-pinned)

| Message | Direction | Purpose |
|---|---|---|
| `Hello` | V→C | viewer pubkey + nonce |
| `Challenge` | C→V | camera nonce, signed |
| `Auth` | V→C | signature over both nonces |
| `Presence` | C→V | camera online/available/busy |
| `Offer` / `Answer` | both | SDP, **with committed DTLS fingerprint** |
| `Candidate` | both | trickle ICE |
| `Bye` | both | typed disconnect reason |

Disconnect reasons, modeled on Alfred's set because they are product-level rather than
transport-level and that is what makes an error message useful:
`HANGUP, CAMERA_DISABLED, CAMERA_BUSY, NO_FRAME, ACCESS_DENIED, SESSION_REPLACED,
INCOMPATIBLE, AUTH_FAILED, FINGERPRINT_MISMATCH`.

### 4.2 RPC over the DataChannel

Everything that is not media is this. One envelope:

```protobuf
message RpcMessage {
  enum Type    { REQUEST = 0; RESPONSE = 1; STREAM = 2; NOTIFICATION = 3; ERROR = 4; }
  enum Service { RESERVED = 0; SESSION = 1; SETTINGS = 2; STATUS = 3;
                 MEDIA = 4; AUDIT = 5; ACCESS = 6; }

  Type    type    = 1;
  Service service = 2;
  uint32  control = 3;   // method id, scoped to the service
  uint32  id      = 4;   // correlation id, echoed on the response
  bytes   payload = 5;   // serialized service message
}
```

Dispatcher semantics, following Alfred with one improvement:

- **Unknown service or unknown control id → drop silently.** Forward compatibility: a
  newer viewer talking to an older camera degrades instead of erroring.
- **Known method that fails → `ERROR` response** carrying a code. Alfred drops these too,
  which makes debugging miserable; we don't.
- Responses echo `service`, `control` and `id`.

**Control ids are allocated in sparse blocks** — `0–15` core, `16–31` detection,
`32–47` storage, `64+` later additions. This is how Alfred added features for a decade
without renumbering anything, and it costs nothing to adopt on day one.

| Service | id | Message |
|---|---|---|
| `SESSION` | 0 | `SessionConfig { bool video, bool audio, Mode mode }` — first RPC of every session |
| | 1 | `PushToTalk` |
| | 2 | `ManualRecord` |
| | 4 | `Torch` |
| | 5 | `Orientation` |
| | 8 | `Resolution` |
| | 9 | `Zoom` |
| | 10 | `Lens` |
| `SETTINGS` | 0 | `SettingsSnapshot` (read-all) |
| | 1 | `Armed` |
| | 2 | `Alias` |
| | 3 | `Microphone` |
| | 4 | `LowLight` |
| | 16 | `DetectionMode` |
| | 17 | `DetectionSchedule` |
| | 18 | `DetectionZone` |
| | 19 | `PrivacyMask` |
| | 22 | `BoundingBoxOverlay` |
| | 32 | `RetentionPolicy` |
| `STATUS` | 0 | `RuntimeStatus` (NOTIFICATION: battery, temp, free space, fps, uptime) |
| | 1 | `SessionStatus` |
| `MEDIA` | 1 | `ClipList` |
| | 2 | `ClipDelete` |
| | 3 | `Thumbnail` |
| | 4 | `ClipData` (STREAM) |
| | 5 | `DateList` |
| | 6 | `TimelineQuery` |
| | 7 | `ClipPin` |
| `AUDIT` | 0 | `AuditQuery` |
| | 1 | `AuditEvent` (NOTIFICATION) |
| `ACCESS` | 0 | `ViewerList` |
| | 1 | `RevokeViewer` |
| | 2 | `PairingMode` |

---

## 5. Storage and retention

Clips are fragmented MP4 in app-private storage, indexed in Room.

**Retention = whichever limit bites first.** Three configurable knobs, all editable in the
camera app:

| Setting | Default | Meaning |
|---|---|---|
| `maxAgeDays` | 14 | delete clips older than this |
| `maxSizeGb` | 32 | total clip bytes ceiling |
| `minFreeSpaceGb` | 8 | hard floor on device free space; prune regardless |

Prune runs after every clip write and on a 15-minute WorkManager job:

1. delete clips older than `maxAgeDays`
2. while total bytes > `maxSizeGb`, delete oldest
3. while device free space < `minFreeSpaceGb`, delete oldest

**Pinned clips are exempt from all three** and are surfaced separately, with a warning if
pinned clips alone approach the size ceiling. Without this, the one clip that mattered is
the one that ages out.

The camera UI shows projected retention at the current recording rate ("≈ 9 days at
current rate"), which is far more useful than the raw numbers.

---

## 6. Media and detection pipeline

**One H.264 encode feeds both the live session and the recorder.** This is Alfred's key
battery/thermal trick and the main reason a 2020 phone can do this continuously.

```
CameraX ──┬── Preview/GL ── privacy mask ── H.264 encoder ──┬── WebRTC track
          │                                                 └── MP4 muxer
          └── ImageAnalysis (downscaled, 2–5 fps)
                  └── motion (frame diff) ──gate──► TFLite detector ──► metadata track
```

- Detection runs on **downscaled frames at 2–5 fps**, gated behind cheap frame
  differencing. Running the detector on every frame is the fastest way to cook the phone.
- **Detections are muxed into the MP4 as a timed-metadata track**, so a clip is one
  self-describing artifact: copy it anywhere and the boxes travel with it. The viewer
  draws overlays straight from the file with no sidecar and no server.
- Model: an openly-licensed detector (EfficientDet-Lite0 or MobileDet) retrained or
  mapped onto `person / vehicle / pet / package`. Ships in-repo under `models/`.

**Known risk:** feeding CameraX output into WebRTC needs a custom `VideoCapturer`;
Alfred used raw Camera2 + GL for this. If CameraX fights us at M4, fall back to Camera2.

---

## 7. Milestones

Each is a commit with a verification command. `source ~/android-tooling/env.sh` first.

| M | Deliverable | Verify |
|---|---|---|
| **M0** | Gradle skeleton, two modules, protobuf codegen, role picker | `./gradlew :core:test assembleDebug` |
| **M1** | **Identity + pairing.** Keystore keypair, QR gen/scan, one-time token, allowlist in Room, roster UI + revoke | Pair; roster shows 1; revoke; re-pair with old QR fails |
| **M2** | **Signaling.** WSS server + pinned cert, mDNS, mutual auth, presence | Viewer discovers and connects; unpaired key refused; tampered cert refused |
| **M3** | **RPC.** Envelope, dispatcher, registry, `STATUS` service | `./gradlew :core:test` (JVM round-trip) + status on device |
| **M4** | **Live video.** CameraX→WebRTC, DTLS fingerprint binding, audit entry, LIVE banner | Video renders; audit entry appears; fingerprint mismatch tears down |
| **M5** | **Session + settings services.** Torch, zoom, resolution, PTT, arm/disarm, alias, privacy mask | Each control round-trips |
| **M6** | **Recording + retention.** Shared encoder, fragmented MP4, ring buffer, `MEDIA` service | Clips written; prune fires on age *and* size; pinned survives |
| **M7** | **Detection.** Motion gate, TFLite, metadata track, zones/schedule | Walk past camera → event with correct label; boxes render on playback |
| **M8** | **Alerts + remote.** Encrypted push payloads, Tailscale path | Alert on locked phone; live view over cellular |
| **M9** | **Audit + polish.** Full log UI both sides, hash chain, timeline, connection stats | Tamper a log row → chain verification fails |

Security lands at M1–M2, before there is anything worth protecting. That ordering is
deliberate: retrofitting an allowlist onto a working stream is how you end up with a
camera that was briefly open.

---

## 8. Dependency pins

Known-good with the toolchain already installed (`~/android-tooling`, JDK 17,
build-tools 35.0.0 — **no `sdkmanager` step needed**). Verify at M0.

| Thing | Version | Note |
|---|---|---|
| Gradle wrapper | 8.11.1 | wrapper downloads it; system Gradle 8.7 is not used |
| AGP | 8.7.3 | |
| Kotlin | 2.1.x | Compose compiler plugin is built in |
| compileSdk / targetSdk | 35 | 36 would need build-tools 36; nothing we need is in it |
| minSdk | 26 | Android 8 — keeps old phones usable as cameras, which is the point |
| WebRTC | `io.github.webrtc-sdk:android` | maintained prebuilt of Google's libwebrtc |
| Protobuf | `protobuf-kotlin-lite` + protoc plugin | |
| CameraX, Room, WorkManager, Compose BOM | current stable | |
| TFLite | `org.tensorflow:tensorflow-lite` + GPU delegate | |

Dependencies are pinned to exact versions in a Gradle version catalog
(`gradle/libs.versions.toml`), and `gradle/verification-metadata.xml` locks checksums.

---

## 9. Open risks

1. **CameraX → WebRTC capturer** (§6). Fall back to Camera2 + GL.
2. **Thermals on the S20 Ultra** under continuous encode + inference. Mitigations: cap
   at 720p when nobody is watching, detection at 2–5 fps, screen off. Measure at M7;
   `RuntimeStatus` already reports temperature, so make that graph early.
3. **Android killing the camera process** over days. Foreground service +
   battery-optimization exemption + boot receiver; verify with a 72-hour soak before M9.
4. **Doze delaying the encrypted push.** Test on a locked, idle phone specifically — this
   is where alert systems quietly fail.
5. **Tailscale on the camera phone** must survive reboot without interaction.
