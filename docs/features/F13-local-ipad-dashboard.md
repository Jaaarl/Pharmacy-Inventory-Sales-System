# F13 — Secure Local iPad Dashboard

**Phase:** 3 — Local iPad dashboard  
**Status:** MVP specification; network/availability configuration remains open  
**Master reference:** [Section 15](../Medtryx_Product_and_Technical_Specification.md#15-architecture-and-feature-f13--local-ipad-dashboard)

## Purpose

Let an authorized iPad browser view local sales/reports and request exports/backups while the MatePad remains the source of truth.

## Architecture

```text
iPad Safari
    -> local HTTPS API/web UI
        -> shared application/use-case layer
            -> Room/SQLite on MatePad
```

Do not duplicate pricing, tax, inventory, or authorization logic in browser JavaScript.

## Hosting Requirements

- Bind only to the intended private/local interface.
- Start hosting from a visible user action.
- Run as a foreground service with persistent notification, status, connected clients, and stop action.
- Stop/revoke sessions on logout, service stop, configured inactivity, or unsafe network change.
- Never expose the service directly to the public internet.

## Security and Pairing

- Use production HTTPS. Never implement trust-all certificate handling.
- Provision a reviewed local-certificate/trust strategy for the managed iPad.
- Pair through a short-lived, single-use token/QR code.
- Issue revocable, expiring, role-scoped sessions.
- Rate-limit authentication and sensitive endpoints.
- Apply F01 authorization in the server use-case layer.
- Do not leak secrets, full IDs, or backup passphrases into URLs or logs.

## MVP Capabilities

- Read-only sales dashboard and authorized F10 reports.
- Request CSV export according to role.
- Request F11 backup creation; the tablet selects/stores the file.
- View service health and synchronization/current-data time.

All other mutations are denied by default.

## Lifecycle Requirements

Test HarmonyOS foreground-service behavior during app backgrounding, screen lock, sleep, battery optimization, Wi-Fi loss/IP change, service stop, and reboot. Do not promise always-on availability until verified on BTKR-W09.

## Acceptance Criteria

- Actual iPad Safari can pair and use authorized views over HTTPS.
- Replayed/expired tokens and revoked sessions fail.
- Direct API calls cannot bypass read-only restrictions.
- Network/lifecycle failures do not corrupt local data.
- The service is not reachable through an unintended/public interface.
- Dashboard totals match local F10 reports.

## Required Tests

- Test certificate trust, pairing, replay, expiration, login/logout, rate limits, revocation, and role scopes.
- Attempt every write endpoint/action as read-only user and without a session.
- Test malformed/oversized payloads, path traversal, unauthorized LAN access, cleartext requests, and sensitive logs.
- Test multiple tabs/clients while the tablet finalizes a sale.
- Test Wi-Fi loss/rejoin, IP change, screen lock, sleep, backgrounding, stop, and reboot on actual devices.
- Reconcile dashboard views/exports to the tablet database and F10 reports.

## Open Configuration

- Pharmacy-controlled router and iPad certificate-profile permission remain to be confirmed.
- Hosting only while visibly enabled versus whenever powered on remains undecided.

## Dependencies

- Phase 0 physical-device compatibility.
- F01 authentication and authorization.
- F05 internal sales records.
- F10 reports/exports.
- F11 backup request.

