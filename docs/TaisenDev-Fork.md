# TaisenDev fork notes

This fork keeps the upstream server and adds two owner-focused features:

- `addMangaFromUrl` is an authenticated GraphQL mutation. It does not install an extension unless `autoInstallExtension: true` is explicitly sent. `addToLibrary` remains opt-in/out as an argument and defaults to true for the “add manga” operation.
- Source requests use Cloudflare DNS-over-HTTPS by default, bootstrapped with `1.1.1.1` and `1.0.0.1`. Set `SUWAYOMI_CLOUDFLARE_DOH=false` to use the VPS system resolver instead. If the DoH request itself fails, the client falls back to system DNS.

DNS-over-HTTPS changes name resolution only. It does not change the VPS public IP or cure an IP-based Cloudflare WAF/anti-bot block. Suwayomi's existing Cloudflare interceptor and optional FlareSolverr/Byparr integration remain the relevant mechanisms for source challenges.

## GitHub build and deployment

- `taisendev-ci.yml` runs formatting checks, server tests and the full JAR build on pushes and PRs.
- `taisendev-image.yml` builds the JAR and publishes an ARM64 image to `ghcr.io/taisendev/suwayomi-server` on `master` pushes and `taisendev-v*` tags.
- `upstream-sync.yml` checks upstream weekly and opens a PR for review. It does not auto-merge or deploy. A conflict stops the sync workflow so it can be resolved manually.

The JAR includes the upstream WebUI bundle as a fallback for manual administration. This fork does not maintain or customize the WebUI. Extension stores and extensions can also be managed through the server's authenticated GraphQL API.
