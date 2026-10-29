# Security Policy

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security vulnerability.**

Report it privately through
[GitHub Security Advisories](https://github.com/AdrienPVM/ArkensiaCobblemonMods/security/advisories/new),
or by direct message to **RoiCheese** on the
[Arkensia Discord](https://discord.gg/fYmPwMBjdB).

Please include: which mod is affected, the version, what an attacker can achieve, and
the steps to reproduce. You will get an acknowledgement as soon as the report is read.

This is a hobby project maintained by one person. There is no guaranteed response
time, and no bug bounty.

## Supported versions

Only the latest published version of each mod receives fixes. There are no backports.

---

## If you are running these mods on a server

Several of these mods expose network services or handle real money. The defaults are
suitable for a trusted local network, **not** for direct exposure to the internet.
Read this section before deploying.

### CheeseDash — the web dashboard

CheeseDash opens two ports: **44311** (HTTP) and **44312** (WebSocket).

- **Never expose these ports publicly.** Restrict them with a firewall, or reach them
  over an SSH tunnel or a VPN. There is no TLS: the dashboard speaks plain HTTP, so on
  an untrusted network your session token travels in clear text. Put it behind a
  reverse proxy that terminates TLS if you need remote access.
- The **master token** lives in `config/cheesedash.json`. It grants `SUPER_ADMIN`,
  which includes arbitrary server command execution. Treat it like a root password.
  Generate a long random value, never reuse one, and rotate it if it has ever been
  pasted into a chat, a screenshot, or a log.
- `POST /api/v1/server/command` executes arbitrary Minecraft commands. Every call is
  audited, but audit is forensics, not prevention. Give accounts the lowest role that
  does their job: `READ_ONLY` or `OPERATOR` rather than `SUPER_ADMIN`.
- Sessions last 24 hours with a sliding expiry. Log out from shared machines.
- ⚠️ **The login endpoint is not rate-limited.** There is a limiter — 20 failures per
  60 seconds per IP — but `POST /api/v1/auth/login` is dispatched and returned *before*
  the check runs (`WebDashboard.java:114`, ahead of the `isRateLimited` call on line 120),
  and a failed login never calls `recordFailedAuth`. The limiter therefore only covers
  invalid `Authorization` headers on the other routes. A master token can be attacked at
  full speed, so the only thing standing between an attacker and your server is the
  token's own entropy and your firewall. Treat network-level restriction as mandatory,
  not advisory.

### CheeseStore — real payments

**This mod processes real money through Stripe. You are solely responsible for how you
deploy it.** It is provided with no warranty, as stated in the
[LICENSE](LICENSE). It has not been independently audited, and it makes no claim of
PCI-DSS compliance.

Before taking a single payment:

- Your **Stripe secret key** (`sk_live_…`) and **webhook signing secret**
  (`whsec_…`) are stored in the mod's config file. That file must never be committed,
  backed up to a public location, or included in a server pack you distribute.
- The checkout and webhook routes under `/store/api/*` are **public by design** — they
  have to be reachable by Stripe and by buyers. Serve them over HTTPS through a
  reverse proxy. Never expose the admin dashboard on the same open port.
- **Verify webhook signatures in production.** An unauthenticated webhook endpoint
  lets anyone forge a "payment succeeded" event and grant themselves goods for free.
- Test with Stripe test keys (`sk_test_…`) and the Stripe CLI before going live.
- Rotate your keys immediately if they leak, from the Stripe dashboard. Rotating is
  cheap; a compromised key is not.

### Mods that patch third-party code

`CheeseCompatModsFix` and `AetherialBridge` apply mixins to mods they do not own.
These are not security boundaries, and a third-party update can change the code they
target. Re-test after any update to the mods they touch.

### General

- Keep `config/` out of version control. The root `.gitignore` already excludes it.
- Review `config/cheesedash_audit.json` after any suspected compromise. It records the
  IP, timestamp and payload of administrative actions.
- Back up your world before enabling anything that resets dimensions or deletes
  chunks — CheeseGuard can do both.
