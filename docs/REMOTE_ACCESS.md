# Remote access over Tailscale

`PLAN.md` §2.9 lists Tailscale as the optional overlay transport for watching from
outside the house. Nothing in the app links against Tailscale — it is a network path, and
the app's own guarantees do not change when you use it.

## What actually changes

Only how the viewer finds the camera. The pinned certificate, the mutual challenge-
response and the DTLS fingerprint binding all run exactly as they do on the LAN, because
none of them ever trusted the network in the first place.

**mDNS does not cross a tailnet.** Discovery works by multicast on the local segment, so
away from home the viewer cannot browse for the camera. That is why the viewer's camera
settings have a *Remote access* field: enter the camera's Tailscale name (`camera-phone`)
or `name:port`, and the locator tries it after the last-known address and the local scan.
It is tried last on purpose — on a tailnet the overlay address often works from the sofa
too, and routing local traffic over it is a needlessly slow way to be right.

## Setting it up

1. Install Tailscale on both phones and sign both into the same tailnet.
2. On the camera phone, open Android's VPN settings, tap the gear beside Tailscale, and turn
   on **Always-on VPN**; then exempt Tailscale from battery optimisation. Without both it
   will not survive a reboot unattended (`PLAN.md` §9.5). Leave *Block connections without
   VPN* off: with it on, a signed-out Tailscale takes the phone's whole network down, alerts
   included. The same Always-on setting on the viewer saves finding Tailscale off once out.
3. Read the camera's tailnet name from the Tailscale app.
4. In the viewer: open the camera → **Settings** → *Remote access* → enter that name → Save.

Pair on the LAN first. Pairing is time-boxed to 120 seconds and involves reading a QR code
off the camera's screen, so you are standing next to it anyway.

## When Tailscale drops

Once the camera has been on a tailnet, losing it for a few minutes sends a health alert to
every viewer with health alerts on, and its return sends another. Only the camera watches
its own Tailscale: the viewer is in your hand when it matters, so you can fix it there.

## Tailscale ACLs

The key allowlist is the real control and it applies identically over the tailnet: an
unpaired device that reaches the port still gets refused before any application data. A
tailnet ACL restricting port 8443 to your own devices is worth adding as a second layer —
it means a tailnet member cannot even open the socket — but the system does not depend on
it.

## What does not work remotely

- **Discovery**, as above.
- **Alerts** are independent of this: they go through a push endpoint, not the tailnet, and
  work anywhere the phone has a network (see `PLAN.md` §2.8).
