# CI/CD Pipeline Design

## Overview

Two GitHub Actions workflows with different trigger strategies:
- **ci-fast.yml**: Every push — build + unit tests (~3-5 min)
- **ci-full.yml**: PR to main — full pipeline including e2e (~8-12 min)

## Architecture

### ci-fast.yml (push)

```
├── job: yrs-bridge-build    (Rust cargo build --release → .so artifact)
├── job: backend-test        (download .so, Gradle test with PostgreSQL service)
└── job: frontend-build      (npm ci + tsc + vite build)
```

### ci-full.yml (PR to main)

```
├── job: yrs-bridge-build
├── job: backend-test        (with PostgreSQL service)
├── job: frontend-build
└── job: e2e                 (depends on all above)
│   ├── services: postgres
│   ├── start backend (java -jar)
│   ├── start frontend (vite dev)
│   └── npx playwright test
```

## Job Details

| Job | Runner | Setup | Key Steps |
|-----|--------|-------|-----------|
| yrs-bridge-build | ubuntu-latest | dtolnay/rust-toolchain@stable | cargo build --release, upload .so artifact |
| backend-test | ubuntu-latest | actions/setup-java@v4 (temurin 25) | download .so, set YRS_BRIDGE_LIB_PATH, ./gradlew test |
| frontend-build | ubuntu-latest | actions/setup-node@v4 (22) | npm ci, npm run build |
| e2e | ubuntu-latest | Java 25 + Node 22 | services: postgres, start backend + frontend, playwright test |

## Key Decisions

1. **yrs-bridge artifact sharing**: upload-artifact/download-artifact to pass .so between jobs, avoid duplicate Rust compilation
2. **Java 25 preview features**: --enable-preview flag, temurin or oracle JDK 25
3. **PostgreSQL**: GitHub Actions services, same credentials as docker-compose (collabdoc/collabdoc)
4. **E2E readiness**: Health check polling before running Playwright
5. **Playwright browser cache**: actions/cache for browser binaries
6. **Native library path**: Set YRS_BRIDGE_LIB_PATH env var pointing to downloaded .so artifact
