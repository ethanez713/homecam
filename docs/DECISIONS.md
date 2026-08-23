# Decisions that deviate from, or resolve open points in, `PLAN.md`

## D1 — Identity keys are EC P-256, not Ed25519

`PLAN.md` §2.2 specifies Ed25519 identity keys in the Android Keystore. Implemented as
**secp256r1 (P-256) with ECDSA-SHA256** instead.

The plan's actual requirement is that the private key is generated on-device, is
hardware-backed where available, and never leaves. Android Keystore only supports Ed25519
from **API 33**, and only on some vendor implementations; below that an Ed25519 key would
have to live in app storage wrapped by a Keystore AES key — software, exportable in
principle, and weaker than what the plan asks for. P-256 has been Keystore-native since
API 23 and StrongBox-eligible since API 28, so on `minSdk 26` every device gets the
property the plan wanted.

It also makes §2.2's second clause work: the WebSocket's self-signed certificate is
issued *directly from the identity key*, so "bound to its identity key" is an identity
rather than a derivation. Android's TLS stack (Conscrypt) does not accept Ed25519
certificates, so with Ed25519 the cert key and the identity key would have to be
different keys with a signed statement linking them.

Nothing above the `IdentityKey` / `IdentitySigner` interfaces knows the algorithm.

## D2 — Verification phrase is a character code, not words

§2.3 calls for "a short verification phrase" shown on both devices during pairing. It is
rendered as **8 characters in two groups** (`MK4T-9QPX`) over the same ambiguity-free
alphabet as the roster fingerprints, rather than a wordlist.

Both devices display it at the same moment and the user compares them side by side; that
is a matching task, not a recall task, so the memorability a wordlist buys is not used,
and 256 words of embedded data is a maintenance liability. 40 bits over a 120-second
window is far beyond what a relay attack can search.

## D3 — Handshake transcripts are length-prefixed and domain-separated

Every signature covers a canonical transcript in which each field is length-prefixed, so
no two different field splits can produce the same bytes. Camera and viewer signatures
cover the *same* transcript under *different* role labels, so a signature from one role
can never be replayed as the other's.

## D4 — The invite pins the identity key, not the certificate

`PLAN.md` §2.3 puts four things in the QR: the camera's identity public key, its
certificate fingerprint, host/port, and the one-time token. The certificate fingerprint
is gone.

D1 made the TLS certificate an artifact of the identity key — the Keystore issues it from
that key and nothing else. Pinning both is therefore pinning the same secret twice, and
two pins for one key is a state that can disagree with itself for no benefit. The viewer
pins the SubjectPublicKeyInfo and its trust manager checks the served certificate's key
against it, which additionally survives the camera reissuing its certificate.

The transcript still binds a certificate hash, and that binding is not redundant: it is
the SHA-256 of the certificate **actually presented on this connection**, which the viewer
reads off the live TLS session rather than from the QR. That is what makes the signature
say something about *which* channel was authenticated (§2.5's argument, one layer down).
It is why `ViewerHandshake` is constructed in `onOpen` and not before — the value does not
exist until TLS has completed.

Dropping the field also takes the invite from ~280 to ~245 characters, back inside a
version-10 QR.

## D5 — RPC rides the signaling channel, and the DataChannel when one exists

`PLAN.md` §4.2 puts the RPC envelope on the WebRTC DataChannel. Here it goes over
whichever of the two authenticated transports is available, preferring the DataChannel
when a media session is up.

Alfred puts RPC on the DataChannel because its signaling is a cloud relay it does not want
to route control traffic through. Ours is a direct, pinned, mutually authenticated
WebSocket to the camera itself — so the reason does not carry over, and insisting on the
DataChannel would mean **spinning up a peer connection and a media session to read the
battery level or the access roster**. Browsing clips or checking who has access should not
cost a camera wakeup and an H.264 encode.

The DataChannel is still preferred whenever it is open, because when a media session is
running it is the path that survives NAT traversal without the WebSocket needing its own.
Both feed one `RpcPeer` with one registry and one dispatcher; which socket the bytes leave
by is a transport detail, not a second protocol.

## D6 — One encoder continuously; a second one only while somebody is watching

`PLAN.md` §6 makes "one H.264 encode feeds both the live session and the recorder" the
key battery and thermal trick. Implemented as: **the continuous path has exactly one
encoder**, and a second encoder exists only for the minutes a viewer is actually
connected.

With nobody watching — which is the state the camera is in essentially always — there is
no WebRTC encode at all, only the recorder's MediaCodec. That is where the trick pays,
and it is paid in full.

Teeing WebRTC's encoder into the muxer during a live session was the alternative, and it
is worse for a reason that has nothing to do with effort: **WebRTC's bitrate is driven by
congestion control**. Feeding its output to the file would mean a recording that gets
worse because the Wi-Fi was busy — degrading the one artifact that has to be good later,
in exchange for saving encoder time during the rarest state. The recording keeps its own
encoder and its own bitrate.

Both encoders read the same masked frames from the same capture, so the privacy mask and
the camera controls apply identically to each.

## D7 — The audit log keeps a tail marker outside the database

§2.7's hash chain detects an edited or deleted row, and the instrumented test written to
prove it found the gap: **deleting the last row leaves a chain that verifies perfectly.**
A chain proves nothing about entries that were never there.

That is not a corner case. The last row is `CONNECTION_REJECTED` for somebody who was just
refused, or `SESSION_OPENED` for somebody who just looked — truncation is the natural
attack, not an exotic one.

So `AuditLog` also writes `{seq, chainHash}` to `audit-head.bin` in app-private storage
after every append, and `verify()` reports a log shorter than its own marker as tampering
rather than as a short history. It is defence in depth rather than a proof — somebody who
can rewrite the database can rewrite the file next to it — but it turns a silent,
single-statement truncation into one that has to know about and match a second artifact.

Covered by `AuditTamperTest` on device, which deletes from the middle, deletes the tail,
and empties the table.
