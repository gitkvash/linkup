# Deploying to an Oracle Cloud Always Free VM

One arm64 VM runs three containers: the app, Redis, and Caddy, which terminates TLS.
Postgres stays on Supabase. After CI passes on `main`, `.github/workflows/deploy-oracle.yml`
uploads the tested commit, builds the image on the VM (`deploy.sh`) and swaps the container.

Until the `ORACLE_HOST` repository variable is set, the workflow skips itself, so it can run
alongside the Render deployment while you migrate.

## 1. Create the VM

- **Region:** Always Free Ampere capacity exists only in your tenancy's *home region*, and that
  is fixed at signup. Pick the one closest to your Supabase project (check it under Supabase →
  Project Settings). Every query crosses that distance.
- Compute → Instances → Create: image **Canonical Ubuntu 24.04** (aarch64), shape
  **VM.Standard.A1.Flex**. 2 OCPU / 12 GB is plenty; the free allowance is 4 OCPU / 24 GB total.
  Paste your own SSH public key. If it reports "out of capacity", retry later or pick another
  availability domain.
- Networking → the VCN's security list: add ingress rules for **TCP 80**, **TCP 443**, and
  optionally **UDP 443** (HTTP/3), from `0.0.0.0/0`.
- Optional but recommended: upgrade the account to Pay-As-You-Go. Oracle can reclaim Always Free
  VMs that sit nearly idle for 7 days, and PAYG accounts are exempt. You are still billed nothing
  while you stay inside the free limits. Set a budget alert to be sure.

## 2. DNS

Point an A record at the VM's public IP (your own domain, or a free one such as DuckDNS). Caddy
requests the certificate on first start, so the record must resolve before the first deploy.

## 3. Bootstrap the VM (once)

```bash
scp deploy/oracle/bootstrap.sh ubuntu@<vm-ip>:
ssh ubuntu@<vm-ip> 'sudo bash bootstrap.sh'
```

This installs Docker and opens 80/443 in the VM's own iptables. Oracle's Ubuntu image blocks
them there even after the security list allows them. It also creates `/opt/linkup`.

Then, on the VM:

```bash
nano /opt/linkup/.env        # contents from deploy/oracle/env.example
chmod 600 /opt/linkup/.env
```

For push notifications, copy the FCM service-account JSON to `/opt/linkup/secrets/fcm.json`,
`chmod 644` it (the container runs as a non-root user), and set `LINKUP_FCM_CREDENTIALS_PATH`.

## 4. Give GitHub Actions access

Create a key that only the workflow uses:

```bash
ssh-keygen -t ed25519 -f linkup-deploy -N "" -C github-actions-deploy
ssh-copy-id -i linkup-deploy.pub ubuntu@<vm-ip>
# on Windows, which has no ssh-copy-id:
#   Get-Content linkup-deploy.pub | ssh ubuntu@<vm-ip> "cat >> ~/.ssh/authorized_keys"
ssh-keyscan -t ed25519 <vm-ip>     # compare with the fingerprint the Oracle console shows
```

In the GitHub repo, Settings → Secrets and variables → Actions:

| Kind | Name | Value |
|---|---|---|
| Secret | `ORACLE_SSH_KEY` | contents of `linkup-deploy` (the private key) |
| Variable | `ORACLE_HOST` | the VM's public IP |
| Variable | `ORACLE_USER` | `ubuntu` (the default if unset) |
| Variable | `ORACLE_KNOWN_HOSTS` | the `ssh-keyscan` output line |
| Variable | `ORACLE_DOMAIN` | the domain from step 2 (enables the post-deploy smoke test) |

This key can control Docker on the VM, which is effectively root. Delete the local copy once
it is stored in GitHub.

## 5. Deploy

Actions → **Deploy (Oracle)** → Run workflow. After that, every push to `main` deploys once CI is
green. The first build downloads all Maven dependencies and takes a few minutes; later builds
reuse the cached layer.

Each deploy stops the old container before starting the new one, so there are roughly 30s of 502s.
This is on purpose: running two instances at once would exceed the Supabase pooler's
15-connection budget.

## Switching the app over

The phone build talks to whatever `API_BASE_URL` it was built with. Change
`linkup_app/dart_defines.prod.json` to `https://<your-domain>` and rebuild the APK. Then suspend
the Render service so the two deployments don't both run the Modulith event republisher
against the same database.

## Operating it

```bash
cd /opt/linkup
docker compose ps
docker compose logs -f app
docker image ls linkup                                     # the last 5 builds, tagged by commit
docker tag linkup:<sha> linkup:latest && docker compose up -d app   # roll back
```

Rolling back does not undo Flyway migrations. A build older than a migration may refuse to start
because Hibernate validates the schema.
