#!/usr/bin/env bash
set -euo pipefail

echo "==> Installing Temporal CLI"
curl -sSf https://temporal.download/cli.sh | sh
sudo ln -sf "$HOME/.temporalio/bin/temporal" /usr/local/bin/temporal
temporal --version

echo "==> Downloading Maven dependencies"
mvn -B dependency:go-offline

echo "==> Dev container ready"