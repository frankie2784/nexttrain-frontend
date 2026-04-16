#!/usr/bin/env bash
# deploy.sh — push the latest server code to GitHub then rebuild on the RPi.
#
# Usage:
#   ./deploy.sh          # push current branch and rebuild
#   ./deploy.sh --no-push  # skip git push (use when RPi just needs a rebuild)
#
# Requirements (local):
#   - git, ssh
#   - SSH key already added to frankipi (run `ssh-copy-id frankie2784@frankipi` once)
#
# Requirements (RPi):
#   - /nas/github/nexttrain_server must be a git clone of this repo
#   - docker + docker compose (v2) installed

set -euo pipefail

# ── Config ────────────────────────────────────────────────────────────────────
RPI_HOST="frankie2784@frankipi"
RPI_REPO="/nas/github/nexttrain_server"
RPI_SERVER_DIR="${RPI_REPO}/server"

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()    { echo -e "${GREEN}▶${NC} $*"; }
warn()    { echo -e "${YELLOW}⚠${NC}  $*"; }
error()   { echo -e "${RED}✗${NC}  $*"; exit 1; }

# ── Parse args ────────────────────────────────────────────────────────────────
SKIP_PUSH=false
for arg in "$@"; do
  [[ "$arg" == "--no-push" ]] && SKIP_PUSH=true
done

# ── Sanity checks ─────────────────────────────────────────────────────────────
cd "$(git rev-parse --show-toplevel)" || error "Not inside a git repo."

if [[ -n "$(git status --porcelain)" ]]; then
  warn "You have uncommitted changes — only committed code will be deployed."
  git status --short
  echo
fi

# ── Push to GitHub ────────────────────────────────────────────────────────────
if [[ "$SKIP_PUSH" == false ]]; then
  BRANCH="$(git rev-parse --abbrev-ref HEAD)"
  info "Pushing branch '${BRANCH}' to GitHub …"
  git push origin "${BRANCH}"
else
  warn "--no-push: skipping git push, deploying whatever is already on GitHub."
fi

# ── Deploy on RPi ─────────────────────────────────────────────────────────────
info "Connecting to ${RPI_HOST} …"
ssh "${RPI_HOST}" bash -s << EOF
  set -euo pipefail

  echo "── Pulling latest from GitHub …"
  git -C "${RPI_REPO}" pull --ff-only

  echo "── Rebuilding and restarting container …"
  docker compose -f "${RPI_SERVER_DIR}/docker-compose.yml" up --build -d

  echo "── Container status:"
  docker compose -f "${RPI_SERVER_DIR}/docker-compose.yml" ps
EOF

info "Deploy complete. Tail logs with:"
echo "  ssh ${RPI_HOST} 'docker compose -f ${RPI_SERVER_DIR}/docker-compose.yml logs -f'"
