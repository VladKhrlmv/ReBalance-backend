# ReBalance – deployment runbook (Oracle Cloud Always Free)

This folder hosts ReBalance (Spring Boot backend + React SPA) for a closed test of about 20 people
on **one Oracle Cloud "Always Free" Ampere A1 VM** (arm64, Ubuntu 24.04, 1 OCPU / 6 GB RAM)
with Docker Compose. No paid OCI services are used.

Contents

1. [What runs where](#1-what-runs-where)
2. [Oracle Cloud account and home region](#2-oracle-cloud-account-and-home-region)
3. [Network: VCN and security list](#3-network-vcn-and-security-list)
4. [Create the VM](#4-create-the-vm)
5. [Public IP address](#5-public-ip-address)
6. [Prepare the VM (setup-vm.sh)](#6-prepare-the-vm-setup-vmsh)
7. [Container registry access (GHCR)](#7-container-registry-access-ghcr)
8. [GitHub: deploy key, secrets, workflows](#8-github-deploy-key-secrets-workflows)
9. [First deploy](#9-first-deploy)
10. [Address / domain (sslip.io now, real domain later)](#10-address--domain-sslipio-now-real-domain-later)
11. [Day-to-day operations](#11-day-to-day-operations)
12. [Backups and restore](#12-backups-and-restore)
13. [Rotating secrets](#13-rotating-secrets)
14. [Always Free idle-instance reclamation](#14-always-free-idle-instance-reclamation)
15. [Monitoring](#15-monitoring)
16. [Local end-to-end stack](#16-local-end-to-end-stack)
17. [Troubleshooting](#17-troubleshooting)

---

## 1. What runs where

```
Internet ── 80/443 tcp, 443 udp ──> web (Caddy 2)
                                     ├─ automatic HTTPS (Let's Encrypt), HTTP -> HTTPS redirect
                                     ├─ /v1/*  (REST API + STOMP WebSocket /v1/ws) ──> backend:8080
                                     └─ /*     React SPA from /srv (index.html fallback)
                                    backend (Spring Boot, Java 21, profile "prod")
                                     ├─ receipt images in ./data/images (bind mount, uid 10001)
                                     └─ JDBC ──> db (MySQL 8.4, volume db_data)
```

Only Caddy is reachable from outside. Everything is one origin (`https://<SITE_ADDRESS>`), so the
backend needs no CORS configuration (`APP_CORS_ORIGINS` stays empty).

| File | Purpose |
|---|---|
| `docker-compose.yml` | production stack: `db`, `backend`, `web` |
| `Caddyfile` | TLS, reverse proxy, SPA routing, cache + security headers (CSP, HSTS, ...) |
| `.env.example` | every setting, documented. Copied to `.env` (secret, never committed) |
| `setup-vm.sh` | one-time (re-runnable) VM preparation |
| `deploy.sh` | server-side deploy step called by GitHub Actions over SSH |
| `backup/backup.sh`, `backup/restore.sh`, `backup/lib.sh` | nightly backup to OCI Object Storage, restore |
| `backup/rebalance-backup.{service,timer}` | systemd units (daily 03:30 UTC) |
| `compose.local.yml`, `local-up.sh`, `local-up.ps1`, `.env.local` | local end-to-end stack on http://localhost:8088 |

Images (built by GitHub Actions, `linux/arm64`, tags `latest` and the git commit SHA):

* `ghcr.io/vladkhrlmv/rebalance-backend` – from this repo (`Dockerfile`: Temurin 21 JRE + the jar, runs as uid 10001)
* `ghcr.io/vladkhrlmv/rebalance-web` – from the private ReBalanceWeb repo (`caddy:2` + the SPA build)

Expected memory use on the VM: about 1.7-2 GB of 6 GB (MySQL ~0.5 GB, JVM ~1 GB, Caddy, OS).

---

## 2. Oracle Cloud account and home region

1. Sign up at <https://signup.cloud.oracle.com>. A credit card is required for identity
   verification; Always Free resources are not charged. **Do not upgrade to Pay As You Go** for
   this project. The setup below only uses Always Free resources.
2. **The home region is chosen at sign-up and cannot be changed.** Always Free compute instances
   can only be created in the home region. Pick one close to your testers. A1 capacity differs
   between regions (popular ones are often "out of capacity", see [Troubleshooting](#17-troubleshooting)).
3. What this setup uses, compared with the Always Free allowance (check the current numbers in
   [Oracle's Always Free resources page](https://docs.oracle.com/en-us/iaas/Content/FreeTier/resourceref.htm)):

   | Resource | Used here | Always Free allowance |
   |---|---|---|
   | Ampere A1 compute | 1 OCPU, 6 GB | 2 OCPUs, 12 GB in total |
   | Boot volume | 50 GB | 200 GB block + boot storage in total |
   | Volume backups | 0-5 (manual) | 5 |
   | Object Storage | nightly backups, 14 days | 20 GB, 50,000 requests/month |
   | Outbound traffic | small | 10 TB/month |

During the 30-day trial the Console also offers paid shapes. Make sure everything you create is
labelled **"Always Free-eligible"**. Paid resources are deleted when the trial ends.

---

## 3. Network: VCN and security list

The instance wizard can create a VCN for you ("Create new virtual cloud network" + "Create new
public subnet"). Alternatively, use **Networking → Virtual cloud networks → Start VCN Wizard →
"Create VCN with Internet Connectivity"**.

Then open **Networking → Virtual cloud networks → \<your VCN\> → Security Lists → Default Security
List** (the one attached to the public subnet) and make sure these **stateful ingress rules** exist:

| Source CIDR | IP protocol | Destination port | Why |
|---|---|---|---|
| `<your-ip>/32` or `0.0.0.0/0` (see below) | TCP | 22 | SSH |
| `0.0.0.0/0` | TCP | 80 | HTTP: Let's Encrypt challenge + redirect to HTTPS |
| `0.0.0.0/0` | TCP | 443 | HTTPS |
| `0.0.0.0/0` | UDP | 443 | HTTP/3 (optional but configured) |

The default list already contains SSH from `0.0.0.0/0` and some ICMP rules. Keep the ICMP rules.

> **SSH and GitHub Actions.** The deploy workflows connect from GitHub-hosted runners, whose IP
> addresses change all the time. If port 22 only allows your own IP, the automated deploy step
> cannot connect. Choose one option:
> * **Recommended:** allow 22 from `0.0.0.0/0`. OCI Ubuntu images already allow key-only login
>   (no passwords; `setup-vm.sh` warns if password login is enabled). You can also restrict the
>   deploy key to the deploy command ([§8](#8-github-deploy-key-secrets-workflows)).
> * Keep 22 restricted and deploy by hand: `ssh ubuntu@<ip> bash /opt/rebalance/app/deploy/deploy.sh backend`
>   (or `web`). The image build/push jobs still run; only the SSH step will fail.

Opening the VCN is not enough on its own. OCI Ubuntu images also have a host firewall (iptables
REJECT rule). `setup-vm.sh` opens 80/443 there.

---

## 4. Create the VM

**Compute → Instances → Create instance**

1. **Name:** `rebalance`. **Placement:** any availability domain (if one is out of capacity, try another).
2. **Image:** *Change image → Ubuntu → Canonical Ubuntu 24.04* (not "Minimal"). The aarch64 build
   is picked automatically once you choose an Ampere shape.
3. **Shape:** *Change shape → Ampere → VM.Standard.A1.Flex*, **1 OCPU, 6 GB memory**.
4. **Networking:** the public subnet from §3, *Automatically assign public IPv4 address* = on.
5. **SSH keys:** upload your public key (e.g. `~/.ssh/id_ed25519.pub`; create one with
   `ssh-keygen -t ed25519`).
6. **Boot volume:** *Specify a custom boot volume size* = **50 GB** (default ~47 GB is fine too).
7. **Create**, wait until the instance is *Running*, note the **public IP**, then
   `ssh ubuntu@<public-ip>`.

---

## 5. Public IP address

* The **ephemeral** public IP assigned by the wizard stays the same across reboots and
  stop/start. It is lost only when the instance is terminated (or the IP is unassigned).
* For an address that survives rebuilding the VM, use a **reserved public IP** if your tenancy's
  limits allow it: *Networking → IP management → Reserved public IPs → Reserve public IP address*,
  then *Instance → Attached VNICs → \<vnic\> → IPv4 addresses → ⋮ Edit → No public IP → Update*,
  edit again and select *Reserved public IP*. Do this **before** running `setup-vm.sh` (it derives
  `SITE_ADDRESS` from the current IP).

With sslip.io the URL contains the IP (`203-0-113-10.sslip.io`). A new IP means a new URL, which is
one more reason to reserve the IP or move to a real domain later ([§10](#10-address--domain-sslipio-now-real-domain-later)).

---

## 6. Prepare the VM (setup-vm.sh)

```bash
ssh ubuntu@<public-ip>
BRANCH=main          # before the prod-readiness branch is merged: BRANCH=prod-readiness
curl -fsSLO "https://raw.githubusercontent.com/VladKhrlmv/ReBalance-backend/$BRANCH/deploy/setup-vm.sh"
sudo REPO_BRANCH=$BRANCH bash setup-vm.sh
```

If you set up from `prod-readiness`, switch the checkout once that branch is merged, because
deploys pull whatever branch is checked out:
`git -C /opt/rebalance/app switch main && git -C /opt/rebalance/app pull --ff-only`.

The script is idempotent (safe to run again) and:

* installs Docker Engine + the compose plugin from Docker's apt repository (plus git, rclone,
  unattended-upgrades);
* inserts `ACCEPT` rules for 80/tcp, 443/tcp, 443/udp **before** the `REJECT` rule in
  `/etc/iptables/rules.v4` (loaded by netfilter-persistent at boot) and applies them live;
* creates a 2 GB swap file, enables unattended security upgrades (with automatic reboot at
  04:30 UTC if needed; `AUTO_REBOOT=false` to disable);
* clones the repo to `/opt/rebalance/app` (owned by `ubuntu`, so deploys can `git pull`);
* creates `/opt/rebalance/app/deploy/.env` from `.env.example` **if it does not exist**, with
  generated `MYSQL_ROOT_PASSWORD`, `DB_PASSWORD`, `JWT_SECRET` and `SITE_ADDRESS=<ip>.sslip.io`
  (chmod 600);
* creates `deploy/data/images` owned by uid/gid 10001 (the backend's user) and `deploy/backups`;
* installs and starts `rebalance-backup.timer`.

**Log out and back in afterwards** (so `ubuntu` is in the `docker` group), then:

```bash
cd /opt/rebalance/app/deploy
nano .env           # review SITE_ADDRESS; backup settings come later (§12)
```

Save a copy of `.env` in your password manager. You need it to rebuild the VM.

> Never edit tracked files (Caddyfile, docker-compose.yml, ...) on the VM: deploys run
> `git pull --ff-only`, which refuses to overwrite local changes. All configuration goes in `.env`.

---

## 7. Container registry access (GHCR)

The web image comes from a **private** repository, so the VM must log in to `ghcr.io` (this also
covers the backend image if its package is private).

1. GitHub → *Settings → Developer settings → Personal access tokens → Tokens (classic) → Generate
   new token (classic)*. Scope: **only `read:packages`**. Expiration: after the end of the test.
   (Fine-grained tokens do not work for GHCR packages.)
2. On the VM, as `ubuntu` (not with sudo):

   ```bash
   echo '<PAT>' | docker login ghcr.io -u <your-github-username> --password-stdin
   ```

The token is stored in `~/.docker/config.json`. That file is base64-encoded, not encrypted, so
use a token that can only read packages.

---

## 8. GitHub: deploy key, secrets, workflows

### Deploy SSH key

On your own machine:

```bash
ssh-keygen -t ed25519 -f rebalance-deploy -N "" -C "github-actions-deploy"
ssh-copy-id -i rebalance-deploy.pub ubuntu@<public-ip>      # or append the .pub line manually
ssh-keyscan <public-ip> 2>/dev/null > known_hosts_rebalance  # value for DEPLOY_KNOWN_HOSTS
```

Compare the scanned key with the VM's real one (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`
on the VM vs. `ssh-keygen -lf known_hosts_rebalance`).

Optional hardening: in `~ubuntu/.ssh/authorized_keys`, prefix the deploy key's line so it can only
run the deploy script (`deploy.sh` reads `backend`/`web` from the original SSH command):

```
restrict,command="bash /opt/rebalance/app/deploy/deploy.sh" ssh-ed25519 AAAA... github-actions-deploy
```

### Secrets (same values in both repositories)

| Name | Value | Required |
|---|---|---|
| `DEPLOY_HOST` | VM public IP (or its DNS name) | yes |
| `DEPLOY_USER` | `ubuntu` | yes |
| `DEPLOY_SSH_KEY` | full content of the private key file `rebalance-deploy` | yes |
| `DEPLOY_KNOWN_HOSTS` | content of `known_hosts_rebalance` | recommended (without it the host key is trusted on first use, with a warning) |

Where to put them:

* **ReBalance-backend (public):** *Settings → Environments → New environment* `production` →
  *Environment secrets*. You can also add *Required reviewers* there to approve each deploy.
  (Repository secrets work too.)
* **ReBalanceWeb (private):** *Settings → Secrets and variables → Actions → Repository secrets*.
  Environment secrets are not available for private repositories on the GitHub Free plan.

Optional repository/environment **variable** `DEPLOY_PATH` (default `/opt/rebalance/app`).
`GITHUB_TOKEN` is provided automatically; the workflows request `packages: write` themselves.

### Workflows

| Repo | Workflow | Trigger | Does |
|---|---|---|---|
| backend | `ci.yml` | push to any branch except `main`, pull requests from forks, manual | `./mvnw -B verify` (incl. Testcontainers), Docker build smoke test |
| backend | `deploy.yml` | push to `main`, manual | `./mvnw -B verify` → push `rebalance-backend:latest` + `:<sha>` (arm64) → SSH `deploy.sh backend` (`git pull`, `docker compose pull backend`, `up -d`, waits until healthy) |
| web | `ci.yml` | push to any branch except `master`, pull requests from forks, manual | `npm ci`, tests, `npm run build`, CSP check, Docker build smoke test |
| web | `deploy.yml` | push to `master`, manual | same build → push `rebalance-web:latest` + `:<sha>` (arm64) → SSH `deploy.sh web` |

Pushes to `main`/`master` are tested in the deploy workflow itself, so a red test never deploys.
Builds use `INLINE_RUNTIME_CHUNK=false` (required by the CSP) and `GENERATE_SOURCEMAP=false`.

---

## 9. First deploy

1. Push/merge to `main` (backend) and `master` (web). Both *Deploy* workflows build and push their
   image. The SSH step may fail on this very first run (the stack is not up yet). That's expected.
2. On the VM:

   ```bash
   cd /opt/rebalance/app/deploy
   docker compose pull
   docker compose up -d
   docker compose ps            # wait until db and backend show "(healthy)" (1-2 minutes)
   docker compose logs -f backend
   ```

3. Open `https://<SITE_ADDRESS>/`. The first request can take a few seconds while Caddy gets the
   certificate. Check the API: `curl -fsS https://<SITE_ADDRESS>/v1/connect/test`.
4. Register a user, create a group and an expense with a receipt image, and check that
   notifications arrive live (WebSocket).
5. In GitHub, re-run the failed deploy jobs (*Actions → run → Re-run failed jobs*) to check that
   automated deploys work. From now on every push to `main`/`master` deploys automatically.

---

## 10. Address / domain (sslip.io now, real domain later)

* **Now:** `SITE_ADDRESS=<ip-with-dashes>.sslip.io` (e.g. `203-0-113-10.sslip.io`). sslip.io resolves
  that name to the embedded IP, so Let's Encrypt can issue a real certificate without a domain.
* **Later, with a domain:** create an `A` record `rebalance.example.com → <public IP>`, wait until
  `dig +short rebalance.example.com` returns the IP, then:

  ```bash
  cd /opt/rebalance/app/deploy
  sed -i 's|^SITE_ADDRESS=.*|SITE_ADDRESS=rebalance.example.com|' .env
  docker compose up -d web
  docker compose logs -f web     # watch the certificate being obtained
  ```

  To keep the old URL working during the switch, list both:
  `SITE_ADDRESS="rebalance.example.com, 203-0-113-10.sslip.io"`.
  Logins are stored per origin, so users have to log in again on the new address.

---

## 11. Day-to-day operations

All commands are run on the VM in `/opt/rebalance/app/deploy` as `ubuntu`.

| Task | Command |
|---|---|
| Status / health | `docker compose ps` |
| Logs (follow) | `docker compose logs -f backend` (or `web`, `db`; add `--since 1h`, `--tail 200`) |
| Restart backend | `docker compose restart backend` |
| Apply `.env` changes | `docker compose up -d` (recreates only the changed services) |
| Deploy by hand | `bash deploy.sh backend` / `bash deploy.sh web` |
| Stop everything (data kept) | `docker compose down` → start again with `docker compose up -d` |
| MySQL shell | `docker compose exec db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" rebalance'` |
| Disk usage | `df -h /` and `docker system df` |

> **Never run `docker compose down -v`** on the VM: `-v` deletes the database volume.

**Admin password reset** (there is no e-mail service). This prints a temporary password, signs
the user out everywhere and exits:

```bash
docker compose run --rm --no-deps backend --spring.main.web-application-type=none --reset-password=user@example.com
```

Send the temporary password to the user over a private channel. They change it via
*account menu → Change password*.

**Rollback** to an earlier build (image tags are commit SHAs, see GitHub → Packages):

```bash
echo 'BACKEND_IMAGE=ghcr.io/vladkhrlmv/rebalance-backend:<sha>' >> .env   # or WEB_IMAGE=...
docker compose up -d
# afterwards remove the line from .env again, otherwise later deploys keep using the pinned image
```

Database migrations (Flyway) only go forward. Rolling back the backend across a migration may
fail to start; in that case restore the backup taken before the deploy ([§12](#12-backups-and-restore)).

---

## 12. Backups and restore

**What:** every night at 03:30 UTC (`rebalance-backup.timer`) `backup/backup.sh`

1. dumps the database (`mysqldump --single-transaction --routines --triggers --events`, gzip) → `backups/db-<UTC>.sql.gz`,
2. archives the receipt images → `backups/images-<UTC>.tar.gz`,
3. uploads both to `BACKUP_REMOTE` (OCI Object Storage via its S3-compatible API, with rclone),
4. deletes backups older than `BACKUP_RETENTION_DAYS` (14) locally and in the bucket.

Any error makes the job fail: the systemd unit shows as failed, and the optional
`BACKUP_PING_URL` receives a `/fail` ping. If no remote is configured, the local backup is still
made, but the job **fails on purpose** so you notice that nothing leaves the VM.

### One-time setup: bucket + S3 keys

1. **Bucket:** *Storage → Object Storage & Archive Storage → Buckets → Create bucket*
   `rebalance-backups`, default storage tier *Standard*, visibility private (default), in the home region.
2. **Namespace + region:** shown on the bucket's details page (*Namespace*). The region
   identifier is shown in the Console's region menu (e.g. `eu-frankfurt-1`).
3. **Customer secret key** (S3 credentials): *Profile menu (top right) → My profile → Customer
   secret keys* (in the newer Console: *Tokens and keys* tab) → *Generate secret key*, name
   `rebalance-backup`. **Copy the secret now** (it is shown once). The *Access key* appears in the
   list afterwards. (Optional, stricter: create a separate IAM user that can only manage objects in
   this bucket and generate the key for that user.)
4. Fill in `deploy/.env` on the VM:

   ```dotenv
   BACKUP_REMOTE=oci:rebalance-backups
   RCLONE_CONFIG_OCI_ENDPOINT=https://<namespace>.compat.objectstorage.<region>.oraclecloud.com
   RCLONE_CONFIG_OCI_REGION=<region>
   RCLONE_CONFIG_OCI_ACCESS_KEY_ID=<access key>
   RCLONE_CONFIG_OCI_SECRET_ACCESS_KEY=<secret>
   ```

   (`RCLONE_CONFIG_OCI_TYPE=s3`, `PROVIDER=Other`, `FORCE_PATH_STYLE=true` and
   `NO_CHECK_BUCKET=true` are already set in `.env.example`.)
5. Test it:

   ```bash
   sudo bash /opt/rebalance/app/deploy/backup/backup.sh
   sudo bash /opt/rebalance/app/deploy/backup/restore.sh --list     # local + remote listing
   systemctl list-timers rebalance-backup.timer                     # next run
   journalctl -u rebalance-backup.service -n 100                    # logs of past runs
   ```

6. Optional dead-man's switch: create a free check at <https://healthchecks.io> (period 1 day,
   grace 2 hours) and put its ping URL in `BACKUP_PING_URL`. You get an e-mail if a night is missed
   or fails.

Storage: 14 × (dump + full images archive) must stay below the 20 GB Object Storage allowance.
Check the bucket size with
`sudo bash -c 'source /opt/rebalance/app/deploy/backup/lib.sh && load_backup_env && rclone size "$BACKUP_REMOTE"'`.

### Restore

```bash
sudo bash /opt/rebalance/app/deploy/backup/restore.sh --list
sudo bash /opt/rebalance/app/deploy/backup/restore.sh db-20261016T033012Z.sql.gz images-20261016T033012Z.tar.gz
```

Arguments can be local paths, names in `deploy/backups/`, or object names in the bucket (they are
downloaded first). The script verifies the files and asks you to type `RESTORE`. Then it saves the
current database to `backups/pre-restore-<UTC>.sql.gz`, stops the backend, drops and re-imports the
database, swaps in the images (the old folder is kept as `data/images.pre-restore-<UTC>`) and starts
the backend again. Leave out the images archive to restore only the database; add `-y` to skip the
prompt.

**Restore drill.** Do this once before the test starts, and again after big changes. Create some
data with a receipt image, run `backup.sh`, change or delete that data, restore the backup, and
check that the data and the image are back and that `docker compose logs backend` shows no
Flyway errors.

### Rebuild from scratch (VM lost, reclaimed or broken)

About 20-30 minutes:

1. Create a new instance (§4). Re-attach the reserved IP if you have one (§5).
2. Run `setup-vm.sh` (§6). Replace the generated `deploy/.env` with your saved copy
   (`chmod 600 .env`). A freshly generated one also works, but everyone has to log in again
   (new `JWT_SECRET`) and you must re-enter the backup keys.
3. `docker login ghcr.io` (§7), then `cd /opt/rebalance/app/deploy && docker compose pull && docker compose up -d`.
4. `sudo bash backup/restore.sh --list`, then `sudo bash backup/restore.sh -y <db-...sql.gz> <images-...tar.gz>`.
5. If the IP changed: update `SITE_ADDRESS` (sslip.io), the GitHub secrets `DEPLOY_HOST` and
   `DEPLOY_KNOWN_HOSTS` (the host key always changes with a new VM), and tell the testers the new URL.

### Boot volume backups (extra safety net)

Always Free includes **5 volume backups** in total. Before risky changes (OS release upgrade, big
migrations): *Storage → Block Storage → Boot volumes → \<instance boot volume\> → Boot volume
backups → Create boot volume backup* (type *Full*). Delete old ones so you stay at 5 or fewer. Do
not assign the predefined Bronze/Silver/Gold backup policies: they keep more than 5 backups. To
restore: create a boot volume from the backup, then create an instance from that boot volume. A
boot volume backup is crash-consistent; the nightly dumps remain the primary backup.

---

## 13. Rotating secrets

Run these in `/opt/rebalance/app/deploy`. Update your password-manager copy of `.env` afterwards.

* **JWT_SECRET.** All users are logged out.

  ```bash
  sed -i "s|^JWT_SECRET=.*|JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')|" .env
  docker compose up -d backend
  ```

* **DB_PASSWORD** (application user). Change it in MySQL first, then in `.env`:

  ```bash
  NEW=$(openssl rand -hex 24)
  docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' <<<"ALTER USER 'rebalance'@'%' IDENTIFIED BY '$NEW';"
  sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$NEW|" .env
  docker compose up -d          # recreates db (its env changed; data is kept) and backend
  ```

* **MYSQL_ROOT_PASSWORD.** Same pattern:

  ```bash
  NEW=$(openssl rand -hex 24)
  docker compose exec -T db sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' <<<"ALTER USER 'root'@'%' IDENTIFIED BY '$NEW'; ALTER USER 'root'@'localhost' IDENTIFIED BY '$NEW';"
  sed -i "s|^MYSQL_ROOT_PASSWORD=.*|MYSQL_ROOT_PASSWORD=$NEW|" .env
  docker compose up -d db
  ```

  (`MYSQL_*` variables only initialise an empty volume. Editing `.env` alone does not change an
  existing database.)

* **GHCR token:** create a new classic PAT (`read:packages`), run `docker login ghcr.io` again,
  then delete the old token.
* **Deploy SSH key:** generate a new pair, add the public key to `authorized_keys`, update
  `DEPLOY_SSH_KEY` in both repos, then remove the old line from `authorized_keys`.
* **Object Storage keys:** generate a new customer secret key, update `RCLONE_CONFIG_OCI_*` in
  `.env`, run `backup.sh` once, then delete the old key in the Console (at most 2 keys per user).

---

## 14. Always Free idle-instance reclamation

Oracle may **reclaim idle Always Free instances**. An instance counts as idle if, over 7 days,
**all** of these are true: CPU utilisation (95th percentile) < 20 %, network utilisation < 20 %,
and, for A1 shapes, **memory utilisation < 20 %**.

A 20-person test will not push CPU or network above 20 %, so **memory is what keeps the instance
from being considered idle**. That's why `JAVA_OPTS` uses a fixed, pre-touched heap
(`-Xms768m -Xmx768m -XX:+AlwaysPreTouch`). Together with MySQL (256 MB buffer pool), Caddy and the
OS, the VM uses about 1.7-2 GB of 6 GB (about 30 %).

* Check: *Instance → Metrics → Memory utilization* (uses the Oracle Cloud Agent "Compute Instance
  Monitoring" plugin, enabled by default on platform images) or `free -m` on the VM.
* If you lower the heap or the shape's memory, keep the memory metric above roughly 25 %.
* If the instance is reclaimed anyway, Oracle sends an e-mail and the instance is stopped. Start
  it again from the Console. If it is gone, [rebuild from backups](#rebuild-from-scratch-vm-lost-reclaimed-or-broken).
  This is why backups must leave the VM.

---

## 15. Monitoring

* **Uptime:** free [UptimeRobot](https://uptimerobot.com) HTTP(S) monitor on
  `https://<SITE_ADDRESS>/v1/connect/test` (5-minute interval, e-mail alert). It also catches
  expired certificates and a stopped VM.
* **Backups:** `BACKUP_PING_URL` with healthchecks.io (§12), or check
  `systemctl status rebalance-backup.service`.
* **Logs:** `docker compose logs`. Each container's log is rotated at 10 MB × 5 files.
  Caddy access logs are JSON on stdout (`docker compose logs web`).
* **Certificates:** Caddy renews automatically about 30 days before expiry:
  `docker compose logs web | grep -i certificate`.

---

## 16. Local end-to-end stack

Runs the production configuration (profile `prod`, same Caddyfile, CSP, MySQL 8.4) on your machine,
with images built from local sources. You need Docker Desktop (compose ≥ 2.24), JDK 21 and Node.js.
The frontend repo must be checked out next to this one (`ReBalance/ReBalanceWeb`).

```bash
bash deploy/local-up.sh            # Linux / macOS / Git Bash
.\deploy\local-up.ps1              # Windows PowerShell (if blocked: powershell -ExecutionPolicy Bypass -File .\deploy\local-up.ps1)
```

The scripts run `./mvnw -DskipTests package`, `npm ci` (first time) + `npm run build` (with
`INLINE_RUNTIME_CHUNK=false`), then
`docker compose -f docker-compose.yml -f compose.local.yml --env-file .env.local up -d --build`.
Open <http://localhost:8088>. `.env.local` holds throwaway development values and is committed on
purpose. Never use those values on the VM. Stop with `... down`, reset all local data with
`... down -v`. `SKIP_BUILD=1` / `-SkipBuild` only rebuilds the images.

---

## 17. Troubleshooting

**"Out of capacity for shape VM.Standard.A1.Flex" / "Out of host capacity"** when creating the VM:
this is a temporary shortage of free A1 hosts in your home region. Try another availability domain,
try again later (early morning or late evening often works), and keep retrying for a while. 1 OCPU /
6 GB is already a small request. (Upgrading the account would avoid this, but is out of scope here.)

**Site not reachable.** Check from the inside out:

1. `docker compose ps`: is `web` running? `curl -sI http://localhost` on the VM should answer
   with a redirect to https.
2. Host firewall: `sudo iptables -L INPUT -n --line-numbers`. The ACCEPT rules for 80/443 must come
   **before** the `REJECT` line. Run `sudo bash setup-vm.sh` again to fix them.
3. OCI: security list ingress rules (§3), and the subnet's route table must have
   `0.0.0.0/0 → Internet Gateway` (the VCN wizard does this).
4. From your machine: `curl -v http://<public-ip>/`.

**Caddy can't get a certificate** (`docker compose logs web | grep -iE 'acme|certificate|error'`):

* ports 80/443 blocked (see above). Let's Encrypt must reach the VM on 80 or 443;
* `SITE_ADDRESS` does not match the VM's current public IP (sslip.io name) or the domain's DNS
  record does not point to the VM yet (`dig +short <name>`);
* rate limits after many failed attempts: fix the cause, then wait (Caddy retries with backoff).
  Don't delete the `caddy_data` volume without a reason: it holds the certificates.

**502 Bad Gateway.** The backend is not running or not healthy. Check `docker compose ps` and
`docker compose logs --tail 200 backend`. Typical causes: `DB_PASSWORD` in `.env` changed without
`ALTER USER` (§13), missing or too short `JWT_SECRET`, a failed Flyway migration, or out of memory
(`docker inspect -f '{{.State.OOMKilled}}' rebalance-backend-1`).

**WebSocket / live notifications don't work.** In the browser's DevTools → Network → *WS*, the
request to `wss://<host>/v1/ws` must return `101 Switching Protocols`. Caddy proxies WebSocket
upgrades automatically; there is nothing to configure. A 401/403 there is a backend (auth) issue
(`docker compose logs backend`). A CSP error in the console means the SPA is calling a different
host. It must use the page's own host. If you later put a CDN/proxy (e.g. Cloudflare) in front,
enable WebSockets there.

**Images can't be saved** (`AccessDeniedException` in the logs):
`sudo chown -R 10001:10001 /opt/rebalance/app/deploy/data/images`.
**Upload rejected with 413:** the file is bigger than the 5 MB limit.

**`docker compose pull`: denied / unauthorized.** Log in to `ghcr.io` as `ubuntu` (not with
sudo) with a valid `read:packages` token (§7).

**Deploy job fails at SSH:** "Connection timed out" means port 22 is not reachable from GitHub
runners (§3) or `DEPLOY_HOST` is wrong. "Host key verification failed" means `DEPLOY_KNOWN_HOSTS`
is outdated (rebuilt VM). "Permission denied (publickey)" means the deploy key is not in
`~ubuntu/.ssh/authorized_keys`.

**Deploy fails at `git pull --ff-only`.** Someone changed tracked files on the VM:
`git -C /opt/rebalance/app status`, undo with `git -C /opt/rebalance/app checkout -- <file>`.

**Backup failed:** `systemctl status rebalance-backup.service` and
`journalctl -u rebalance-backup.service -n 200`. Typical causes: wrong `RCLONE_CONFIG_OCI_*` values
(endpoint must contain namespace **and** region), an expired or deleted customer secret key, or
the `db` container not running.
