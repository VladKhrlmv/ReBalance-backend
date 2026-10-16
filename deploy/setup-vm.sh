#!/usr/bin/env bash
# One-time (and safe to re-run) preparation of a fresh Oracle Cloud Ubuntu 24.04 (arm64, A1) VM.
#
#   curl -fsSLO https://raw.githubusercontent.com/VladKhrlmv/ReBalance-backend/main/deploy/setup-vm.sh
#   sudo bash setup-vm.sh
#
# Options (environment variables, pass them after sudo: `sudo REPO_BRANCH=x bash setup-vm.sh`):
#   REPO_URL      git URL of the backend repo   (default: https://github.com/VladKhrlmv/ReBalance-backend.git)
#   REPO_BRANCH   branch to check out           (default: main)
#   APP_DIR       checkout location             (default: /opt/rebalance/app)
#   DEPLOY_USER   user that runs docker compose and receives GitHub deploys
#                 (default: the user who invoked sudo, usually "ubuntu")
#   SWAP_SIZE     swap file size                (default: 2G)
#   AUTO_REBOOT   reboot at 04:30 UTC when security updates require it (default: true)
#
# What it does: Docker Engine + compose plugin (Docker's apt repo), opens 80/443 tcp + 443 udp
# in the instance's iptables (persisted in /etc/iptables/rules.v4), 2 GB swap,
# unattended security upgrades, clones/pulls the repo, creates deploy/.env with generated
# secrets (only if missing), creates data/images (uid 10001) and installs the backup timer.
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/VladKhrlmv/ReBalance-backend.git}"
REPO_BRANCH="${REPO_BRANCH:-main}"
APP_DIR="${APP_DIR:-/opt/rebalance/app}"
SWAP_SIZE="${SWAP_SIZE:-2G}"
AUTO_REBOOT="${AUTO_REBOOT:-true}"
APP_UID=10001
APP_GID=10001

log() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mWARNING: %s\033[0m\n' "$*" >&2; }
die() { printf '\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo: sudo bash $0"
DEPLOY_USER="${DEPLOY_USER:-${SUDO_USER:-ubuntu}}"
[[ $DEPLOY_USER != root ]] || die "run as a normal sudo user (e.g. ubuntu), not as root - or set DEPLOY_USER"
id "$DEPLOY_USER" >/dev/null 2>&1 || die "user $DEPLOY_USER does not exist"

# shellcheck source=/dev/null
. /etc/os-release
[[ ${ID:-} == ubuntu ]] || warn "this script targets Ubuntu 24.04; detected ${PRETTY_NAME:-unknown}"
[[ $(uname -m) == aarch64 ]] || warn "expected an arm64 (Ampere A1) VM; detected $(uname -m) - CI images are arm64-only"

export DEBIAN_FRONTEND=noninteractive
as_user() { sudo -u "$DEPLOY_USER" -H "$@"; }

# ---------------------------------------------------------------------------
log "Installing base packages"
# Keep iptables-persistent from asking (and from saving Docker's runtime rules).
echo "iptables-persistent iptables-persistent/autosave_v4 boolean false" | debconf-set-selections
echo "iptables-persistent iptables-persistent/autosave_v6 boolean false" | debconf-set-selections
apt-get update -q
apt-get install -y -q ca-certificates curl git openssl gzip tar util-linux \
  iptables iptables-persistent netfilter-persistent unattended-upgrades rclone

# ---------------------------------------------------------------------------
log "Installing Docker Engine + compose plugin (download.docker.com)"
for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
  if dpkg-query -W -f='${Status}' "$pkg" 2>/dev/null | grep -q "install ok installed"; then
    apt-get remove -y -q "$pkg"
  fi
done
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat >/etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${UBUNTU_CODENAME:-$VERSION_CODENAME}
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update -q
apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

if [[ ! -f /etc/docker/daemon.json ]]; then
  # Default log rotation for containers not started through compose (e.g. one-off runs).
  cat >/etc/docker/daemon.json <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
EOF
  systemctl restart docker
fi
systemctl enable --now docker containerd >/dev/null
if ! id -nG "$DEPLOY_USER" | tr ' ' '\n' | grep -qx docker; then
  usermod -aG docker "$DEPLOY_USER"
  NEED_RELOGIN=1
fi
docker compose version

# ---------------------------------------------------------------------------
log "Opening 80/tcp, 443/tcp, 443/udp in iptables"
# OCI Ubuntu images ship /etc/iptables/rules.v4 with a final "-A INPUT -j REJECT"; rules must go
# before it. We edit the file (persistence, loaded by netfilter-persistent at boot) and insert
# the same rules live. `netfilter-persistent save` is NOT used: it would also persist Docker's
# runtime rules. Remember to also allow these ports in the VCN security list (see README).
RULES_FILE=/etc/iptables/rules.v4
FW_RULES=(
  "-p tcp -m state --state NEW -m tcp --dport 80 -j ACCEPT"
  "-p tcp -m state --state NEW -m tcp --dport 443 -j ACCEPT"
  "-p udp -m udp --dport 443 -j ACCEPT"
)
if [[ -f $RULES_FILE ]] && grep -qE '^-A INPUT .*-j REJECT' "$RULES_FILE"; then
  [[ -f $RULES_FILE.pre-rebalance ]] || cp -p "$RULES_FILE" "$RULES_FILE.pre-rebalance"
  for rule in "${FW_RULES[@]}"; do
    line="-A INPUT $rule"
    if ! grep -qxF -- "$line" "$RULES_FILE"; then
      tmp="$(mktemp)"
      awk -v new="$line" '!done && /^-A INPUT .*-j REJECT/ { print new; done = 1 } { print }' "$RULES_FILE" >"$tmp"
      cat "$tmp" >"$RULES_FILE" && rm -f "$tmp"
      echo "persisted: $line"
    fi
  done
else
  warn "$RULES_FILE has no INPUT REJECT rule - nothing to persist"
fi
for rule in "${FW_RULES[@]}"; do
  read -r -a spec <<<"$rule"
  if ! iptables -C INPUT "${spec[@]}" 2>/dev/null; then
    pos="$(iptables -L INPUT -n --line-numbers | awk '$2 == "REJECT" { print $1; exit }')"
    if [[ -n $pos ]]; then
      iptables -I INPUT "$pos" "${spec[@]}"
      echo "live: inserted '$rule' at position $pos"
    fi
  fi
done
systemctl enable netfilter-persistent >/dev/null 2>&1 || true

# ---------------------------------------------------------------------------
log "Swap ($SWAP_SIZE at /swapfile)"
if ! swapon --show=NAME --noheadings | grep -qx /swapfile; then
  if [[ ! -f /swapfile ]]; then
    fallocate -l "$SWAP_SIZE" /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
  fi
  swapon /swapfile
fi
grep -qE '^/swapfile[[:space:]]' /etc/fstab || echo '/swapfile none swap sw 0 0' >>/etc/fstab
echo 'vm.swappiness=10' >/etc/sysctl.d/99-rebalance.conf
sysctl -q -p /etc/sysctl.d/99-rebalance.conf
swapon --show

# ---------------------------------------------------------------------------
log "Unattended security upgrades"
cat >/etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
EOF
cat >/etc/apt/apt.conf.d/52rebalance-unattended-upgrades <<EOF
// Managed by ReBalance setup-vm.sh
Unattended-Upgrade::Remove-Unused-Kernel-Packages "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
Unattended-Upgrade::Automatic-Reboot "$AUTO_REBOOT";
Unattended-Upgrade::Automatic-Reboot-Time "04:30";
EOF
systemctl enable --now unattended-upgrades >/dev/null

# ---------------------------------------------------------------------------
log "Repository checkout in $APP_DIR ($REPO_BRANCH)"
install -d -m 755 "$(dirname "$APP_DIR")"
if [[ -d $APP_DIR/.git ]]; then
  # The deploy user must own the checkout (GitHub deploys run `git pull` as that user);
  # runtime data keeps its own ownership.
  find "$APP_DIR" \( -path "$APP_DIR/deploy/data" -o -path "$APP_DIR/deploy/backups" \) -prune \
    -o -exec chown "$DEPLOY_USER:$DEPLOY_USER" {} +
  as_user git -C "$APP_DIR" pull --ff-only || warn "git pull failed - keeping the existing checkout"
else
  [[ ! -e $APP_DIR ]] || die "$APP_DIR exists but is not a git checkout"
  install -d -o "$DEPLOY_USER" -g "$DEPLOY_USER" -m 755 "$APP_DIR"
  as_user git clone --branch "$REPO_BRANCH" "$REPO_URL" "$APP_DIR"
fi
DEPLOY_DIR="$APP_DIR/deploy"
[[ -f $DEPLOY_DIR/docker-compose.yml ]] || die "$DEPLOY_DIR/docker-compose.yml not found - wrong branch?"

# ---------------------------------------------------------------------------
log "Configuration ($DEPLOY_DIR/.env)"
ENV_FILE="$DEPLOY_DIR/.env"
set_env() { # key value - replace the KEY=... line (values here never contain '|' or '&')
  sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"
}
if [[ ! -f $ENV_FILE ]]; then
  install -m 600 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$DEPLOY_DIR/.env.example" "$ENV_FILE"
  set_env MYSQL_ROOT_PASSWORD "$(openssl rand -hex 24)"
  set_env DB_PASSWORD "$(openssl rand -hex 24)"
  set_env JWT_SECRET "$(openssl rand -base64 48 | tr -d '\n')"
  if public_ip="$(curl -4 -fsS --max-time 5 https://api.ipify.org 2>/dev/null)" &&
    [[ $public_ip =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then
    set_env SITE_ADDRESS "${public_ip//./-}.sslip.io"
    echo "SITE_ADDRESS set to ${public_ip//./-}.sslip.io (detected public IP $public_ip)"
  else
    warn "could not detect the public IP - set SITE_ADDRESS in $ENV_FILE yourself"
  fi
  echo "created $ENV_FILE with freshly generated secrets"
  ENV_CREATED=1
else
  echo "$ENV_FILE exists - left unchanged"
fi
chown "$DEPLOY_USER:$DEPLOY_USER" "$ENV_FILE"
chmod 600 "$ENV_FILE"

# ---------------------------------------------------------------------------
log "Data directories"
install -d -m 755 "$DEPLOY_DIR/data"
install -d -m 750 -o "$APP_UID" -g "$APP_GID" "$DEPLOY_DIR/data/images"
chown -R "$APP_UID:$APP_GID" "$DEPLOY_DIR/data/images"
install -d -m 700 "$DEPLOY_DIR/backups"
ls -ld "$DEPLOY_DIR/data/images" "$DEPLOY_DIR/backups"

# ---------------------------------------------------------------------------
log "Backup timer (rebalance-backup.timer)"
for unit in rebalance-backup.service rebalance-backup.timer; do
  sed "s#/opt/rebalance/app#$APP_DIR#g" "$DEPLOY_DIR/backup/$unit" >"/etc/systemd/system/$unit"
  chmod 644 "/etc/systemd/system/$unit"
done
systemctl daemon-reload
systemctl enable --now rebalance-backup.timer >/dev/null
systemctl list-timers rebalance-backup.timer --no-pager || true

# ---------------------------------------------------------------------------
if sshd -T 2>/dev/null | grep -qi '^passwordauthentication yes'; then
  warn "SSH password authentication is ENABLED - disable it (PasswordAuthentication no) before opening port 22 widely"
fi

SITE_NOW="$(grep -E '^SITE_ADDRESS=' "$ENV_FILE" | cut -d= -f2- || true)"
cat <<EOF

$(printf '\033[1;32m')Setup complete.$(printf '\033[0m') Next steps (as $DEPLOY_USER, NOT root):

 0. ${NEED_RELOGIN:+Log out and back in once (docker group membership was just added).}
    Check the OCI VCN security list allows 80/tcp, 443/tcp, 443/udp (and 22 for SSH).
 1. Log in to GitHub Container Registry with a classic PAT that has ONLY read:packages
    (the web image comes from a private repo):
       echo <PAT> | docker login ghcr.io -u <github-username> --password-stdin
 2. Review $ENV_FILE  ${ENV_CREATED:+(secrets were generated - store a copy in your password manager)}
       SITE_ADDRESS is currently: ${SITE_NOW:-<unset>}
       Fill BACKUP_REMOTE / RCLONE_CONFIG_OCI_* for off-site backups (README: "Backups").
 3. Start the stack (images must have been pushed by the GitHub deploy workflows):
       cd $DEPLOY_DIR && docker compose pull && docker compose up -d
       docker compose ps && docker compose logs -f backend
 4. Open https://${SITE_NOW:-<SITE_ADDRESS>}/ - the first request may take a few seconds
    while Caddy obtains the certificate.
 5. Test the backup once: sudo bash $DEPLOY_DIR/backup/backup.sh

EOF
