#!/usr/bin/env bash
# One-time setup of a fresh Oracle Cloud VM (Ubuntu 24.04, aarch64). From your machine:
#
#   scp deploy/oracle/bootstrap.sh ubuntu@<vm-ip>:
#   ssh ubuntu@<vm-ip> 'sudo bash bootstrap.sh'
#
# Safe to re-run.
set -euo pipefail

deploy_user=${SUDO_USER:-ubuntu}

# Oracle's Ubuntu images ship an iptables ruleset that REJECTs everything except SSH,
# on top of the VCN security list - both have to let 80/443 through. Insert before that
# REJECT and persist, and do it before Docker is installed so the saved ruleset doesn't
# capture Docker's own chains (Docker re-adds those itself at every start).
open_port() {
  local proto=$1 port=$2
  if ! iptables -C INPUT -p "$proto" --dport "$port" -j ACCEPT 2>/dev/null; then
    local reject
    reject=$(iptables -L INPUT --line-numbers -n | awk '$2 == "REJECT" { print $1; exit }')
    iptables -I INPUT "${reject:-1}" -p "$proto" --dport "$port" -j ACCEPT
  fi
}
open_port tcp 80
open_port tcp 443
open_port udp 443
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y iptables-persistent
netfilter-persistent save

apt-get install -y docker.io docker-compose-v2
systemctl enable --now docker
# Note: docker group membership is root-equivalent. The deploy key gets this too.
usermod -aG docker "$deploy_user"

install -d -o "$deploy_user" -g "$deploy_user" -m 755 /opt/linkup /opt/linkup/secrets

echo
echo "Done. Next, as $deploy_user (log out and back in first, for the docker group):"
echo "  1. create /opt/linkup/.env from deploy/oracle/env.example, then chmod 600 it"
echo "  2. optionally put the FCM service-account JSON at /opt/linkup/secrets/fcm.json (chmod 644)"
echo "  3. run the 'Deploy (Oracle)' workflow"
