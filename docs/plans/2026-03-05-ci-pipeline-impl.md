# CI Pipeline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create GitHub Actions CI workflows: fast (push) for build+unit tests, full (PR) for build+unit+e2e.

**Architecture:** Two workflow files. Jobs share yrs-bridge .so via artifacts. E2E job starts backend+frontend on the runner with PostgreSQL as a GitHub service.

**Tech Stack:** GitHub Actions, Rust (stable), Java 25 (temurin), Node 22, PostgreSQL 17, Playwright

---

### Task 1: Create ci-fast.yml (push workflow)

**Files:**
- Create: `.github/workflows/ci-fast.yml`

**Step 1: Create the workflow file**

```yaml
name: CI Fast

on:
  push:
    branches: [main]

jobs:
  yrs-bridge:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - uses: actions/cache@v4
        with:
          path: yrs-bridge/target
          key: rust-${{ hashFiles('yrs-bridge/Cargo.lock') }}
      - run: cargo build --release
        working-directory: yrs-bridge
      - uses: actions/upload-artifact@v4
        with:
          name: yrs-bridge-so
          path: yrs-bridge/target/release/libyrs_bridge.so
          retention-days: 1

  backend-test:
    runs-on: ubuntu-latest
    needs: yrs-bridge
    services:
      postgres:
        image: postgres:17
        env:
          POSTGRES_DB: collabdoc
          POSTGRES_USER: collabdoc
          POSTGRES_PASSWORD: collabdoc
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: oracle
          java-version: 25
      - uses: actions/download-artifact@v4
        with:
          name: yrs-bridge-so
          path: yrs-bridge/target/release
      - run: chmod +x yrs-bridge/target/release/libyrs_bridge.so
      - name: Run tests
        working-directory: backend
        env:
          YRS_BRIDGE_LIB_PATH: ${{ github.workspace }}/yrs-bridge/target/release/libyrs_bridge.so
        run: ./gradlew test

  frontend-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - run: npm ci
        working-directory: frontend
      - run: npm run build
        working-directory: frontend
```

**Step 2: Verify syntax locally**

Run: `cd /Users/zhongjunjiexiansheng/junjie-dev/collab-doc-new && cat .github/workflows/ci-fast.yml | python3 -c "import sys,yaml; yaml.safe_load(sys.stdin.read()); print('YAML OK')"` (if pyyaml available, otherwise just review manually)

**Step 3: Commit**

```bash
git add .github/workflows/ci-fast.yml
git commit -m "ci: add fast CI workflow (push) with build and unit tests"
```

---

### Task 2: Create ci-full.yml (PR workflow with e2e)

**Files:**
- Create: `.github/workflows/ci-full.yml`

**Step 1: Create the workflow file**

```yaml
name: CI Full

on:
  pull_request:
    branches: [main]

jobs:
  yrs-bridge:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - uses: actions/cache@v4
        with:
          path: yrs-bridge/target
          key: rust-${{ hashFiles('yrs-bridge/Cargo.lock') }}
      - run: cargo build --release
        working-directory: yrs-bridge
      - uses: actions/upload-artifact@v4
        with:
          name: yrs-bridge-so
          path: yrs-bridge/target/release/libyrs_bridge.so
          retention-days: 1

  backend-test:
    runs-on: ubuntu-latest
    needs: yrs-bridge
    services:
      postgres:
        image: postgres:17
        env:
          POSTGRES_DB: collabdoc
          POSTGRES_USER: collabdoc
          POSTGRES_PASSWORD: collabdoc
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: oracle
          java-version: 25
      - uses: actions/download-artifact@v4
        with:
          name: yrs-bridge-so
          path: yrs-bridge/target/release
      - run: chmod +x yrs-bridge/target/release/libyrs_bridge.so
      - name: Run tests
        working-directory: backend
        env:
          YRS_BRIDGE_LIB_PATH: ${{ github.workspace }}/yrs-bridge/target/release/libyrs_bridge.so
        run: ./gradlew test

  frontend-build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - run: npm ci
        working-directory: frontend
      - run: npm run build
        working-directory: frontend

  e2e:
    runs-on: ubuntu-latest
    needs: [yrs-bridge, backend-test, frontend-build]
    services:
      postgres:
        image: postgres:17
        env:
          POSTGRES_DB: collabdoc
          POSTGRES_USER: collabdoc
          POSTGRES_PASSWORD: collabdoc
        ports:
          - 5432:5432
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    steps:
      - uses: actions/checkout@v4

      # Setup Java + download native lib
      - uses: actions/setup-java@v4
        with:
          distribution: oracle
          java-version: 25
      - uses: actions/download-artifact@v4
        with:
          name: yrs-bridge-so
          path: yrs-bridge/target/release
      - run: chmod +x yrs-bridge/target/release/libyrs_bridge.so

      # Build and start backend
      - name: Build backend
        working-directory: backend
        run: ./gradlew bootJar
      - name: Start backend
        working-directory: backend
        env:
          YRS_BRIDGE_LIB_PATH: ${{ github.workspace }}/yrs-bridge/target/release/libyrs_bridge.so
        run: |
          java --enable-preview --enable-native-access=ALL-UNNAMED \
            -jar build/libs/*.jar &
          echo "Waiting for backend..."
          for i in $(seq 1 30); do
            if curl -sf http://localhost:8080/api/auth/login > /dev/null 2>&1 || [ "$(curl -so /dev/null -w '%{http_code}' http://localhost:8080/api/docs 2>/dev/null)" != "000" ]; then
              echo "Backend is up"
              break
            fi
            sleep 2
          done

      # Setup Node + start frontend
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: frontend/package-lock.json
      - run: npm ci
        working-directory: frontend
      - name: Start frontend
        working-directory: frontend
        run: |
          npx vite --host &
          echo "Waiting for frontend..."
          for i in $(seq 1 15); do
            if curl -sf http://localhost:3000 > /dev/null 2>&1; then
              echo "Frontend is up"
              break
            fi
            sleep 2
          done

      # Install Playwright browsers and run tests
      - name: Install Playwright browsers
        working-directory: frontend
        run: npx playwright install --with-deps chromium
      - name: Run e2e tests
        working-directory: frontend
        run: npx playwright test
      - uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: playwright-report
          path: frontend/playwright-report/
          retention-days: 7
```

**Step 2: Verify YAML syntax**

Review the file for correctness. Key things to check:
- `needs` dependencies are correct: e2e depends on all three jobs
- PostgreSQL service config matches docker-compose.yml credentials
- `YRS_BRIDGE_LIB_PATH` points to the downloaded artifact
- Backend health check loop works (waits up to 60s)
- Frontend health check loop works (waits up to 30s)
- Playwright report uploaded on failure for debugging

**Step 3: Commit**

```bash
git add .github/workflows/ci-full.yml
git commit -m "ci: add full CI workflow (PR) with e2e tests"
```

---

### Task 3: Add .gitignore entry for Playwright auth state

**Files:**
- Modify: `frontend/.gitignore` (or create if not exists)

**Step 1: Check current .gitignore**

Check if `e2e/.auth/` is already in `.gitignore`. If not, add it.

**Step 2: Add entry if missing**

Append to `frontend/.gitignore`:
```
e2e/.auth/
```

**Step 3: Commit**

```bash
git add frontend/.gitignore
git commit -m "chore: gitignore Playwright auth state"
```

---

### Task 4: Verify locally and push

**Step 1: Validate both YAML files parse correctly**

Run:
```bash
python3 -c "
import yaml, sys
for f in ['.github/workflows/ci-fast.yml', '.github/workflows/ci-full.yml']:
    with open(f) as fh:
        yaml.safe_load(fh.read())
    print(f'{f}: OK')
"
```

If pyyaml not available, use: `npx yaml-lint .github/workflows/*.yml` or manual review.

**Step 2: Review the commit log**

Run: `git log --oneline -5`

Expected: 3 new commits for ci-fast, ci-full, and .gitignore.

**Step 3: Create GitHub repo and push (requires user action)**

```bash
# User creates repo on GitHub, then:
git remote add origin git@github.com:<user>/<repo>.git
git push -u origin main
```

After push, the ci-fast workflow should trigger automatically. To test ci-full, create a PR branch.
