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
