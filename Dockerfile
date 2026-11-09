# syntax=docker/dockerfile:1

# ---- Build stage: install deps & build with Bun (lockfile is bun.lock) ----
FROM oven/bun:1 AS builder
WORKDIR /app

# TanStack Start builds through Nitro. The node-server preset produces a
# standalone listening server at .output/server/index.mjs (honors PORT, default 3000).
# vite.config.ts reads this env; "vercel" remains the default outside Docker.
ENV NITRO_PRESET=node-server

COPY package.json bun.lock ./
RUN --mount=type=cache,target=/root/.bun/install/cache \
    bun install --frozen-lockfile

COPY . .
RUN bun run build

# ---- Runtime stage: plain Node running only the traced Nitro output ----
FROM node:22-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production \
    PORT=3000 \
    HOST=0.0.0.0

# Nitro copies all runtime-traced deps into .output — no node_modules needed.
COPY --from=builder --chown=node:node /app/.output ./.output

USER node
EXPOSE 3000
CMD ["node", ".output/server/index.mjs"]
