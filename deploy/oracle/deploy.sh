#!/usr/bin/env bash
# Runs ON the VM, from the source tree the deploy workflow has just unpacked into
# /opt/linkup/src:
#
#   bash /opt/linkup/src/deploy/oracle/deploy.sh <commit-sha>
#
# Builds the image here rather than in CI: the VM is arm64, so an image built on GitHub's
# x86 runners would need emulation, and building locally needs no registry or pull token.
# The Dockerfile's dependency layer stays cached between builds.
#
# The old container is stopped before the new one starts, so a deploy costs ~30s of 502s.
# That is deliberate: two instances side by side would exceed the Supabase session
# pooler's 15-connection budget (see DataSourceConfig).
set -euo pipefail

sha=${1:?usage: deploy.sh <commit-sha>}
root=/opt/linkup
cd "$root"

if [[ ! -f .env ]]; then
  echo "$root/.env is missing: copy deploy/oracle/env.example there and fill it in." >&2
  exit 1
fi

# cp, not mv/install: Caddy has the Caddyfile bind-mounted as a single file, and a new
# inode would leave the container reading the old one.
cp src/deploy/oracle/docker-compose.yml docker-compose.yml
cp src/deploy/oracle/Caddyfile Caddyfile

docker build --pull -t "linkup:$sha" src
docker tag "linkup:$sha" linkup:latest

# Compose recreates app because the image behind linkup:latest changed; --wait blocks
# until every service is healthy (or running, for Caddy).
if ! docker compose up -d --remove-orphans --wait --wait-timeout 300; then
  echo "Deploy of $sha did not become healthy. Last app logs:" >&2
  docker compose logs --tail 200 app >&2
  echo "To go back: docker tag linkup:<previous-sha> linkup:latest && docker compose up -d app" >&2
  exit 1
fi

# The Caddyfile may have changed without the caddy container being recreated.
docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile

# Keep the five most recent builds for rollback (newest first in the listing).
docker image ls linkup --format '{{.Tag}}' | grep -vx latest | tail -n +6 \
  | xargs -r -I{} docker image rm "linkup:{}"
docker image prune -f >/dev/null

echo "Deployed $sha"
