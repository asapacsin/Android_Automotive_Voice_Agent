# ADR-001 — Direct on-device provider connection

Status: **Accepted** (supersedes `docs/DECISIONS.md` D12)

## Context

The original design routed realtime voice through a PC backend: the Android app connected to `ws://<backend>/v1/voice/realtime`, the backend held the provider credentials in `backend/.env`, and the phone never saw an API key. D12 recorded this explicitly — "Android never receives AK/SK/token".

That design made the product unusable as a product. The phone could only work while a developer machine was running and reachable on the same network, which is not a car assistant.

## Decision

The Android app connects **directly** to the provider from the device.

- Realtime session: `wss://aip.baidubce.com/ws/2.0/speech/v1/realtime`
- OAuth token (legacy auth mode): `https://aip.baidubce.com/oauth/2.0/token`
- POI lookup for navigation: `https://restapi.amap.com/v3/place/text` and `/place/around`

Credentials are entered by the user on the phone and stored as AES/GCM ciphertext under Android Keystore (`AndroidKeystoreCredentialStore`). They are never placed in source, Gradle, resources, or the APK. The packaged backend URL resource is deliberately empty (`resValue("string", "nova_backend_url", "")`).

Normal operation requires **no** PC, LAN, ADB, or backend process.

## Consequences

- The app is independently usable after the user enters credentials.
- Credential security becomes an on-device concern: Keystore storage, no logging of secrets, and a secret-scan test over sources and the APK.
- The `backend/` directory and the `BackendRealtimeProvider` / `BackendVoiceClient` classes remain in the tree as dormant compatibility code. They are not part of the production path.
- Release builds keep `usesCleartextTraffic="false"`; the debug source set overrides it for local experimentation only.

## What would justify revisiting this decision

- A provider that forbids client-side credentials, or a deployment where keys must not touch the device.
- A fleet/enterprise requirement for central key rotation or per-user revocation.
- A provider protocol that cannot be implemented on-device (for example one requiring a server-side signing step).
