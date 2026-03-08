#!/usr/bin/env bash
set -euo pipefail

echo "Pulling latest code..."
git pull origin main

echo "Building and restarting services..."
docker compose -f docker-compose.prod.yml up --build -d

echo "Cleaning up old images..."
docker image prune -f

echo "Done! Services:"
docker compose -f docker-compose.prod.yml ps
