import { test as setup } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";
const TEST_USER = { username: "e2e_testuser", password: "testpass123" };

setup("authenticate", async ({ request, context }) => {
  // Try to register; if user already exists, login instead
  const registerRes = await request.post(`${API_BASE}/auth/register`, {
    data: TEST_USER,
  });

  if (registerRes.status() === 409) {
    // User already exists — login
    const loginRes = await request.post(`${API_BASE}/auth/login`, {
      data: TEST_USER,
    });
    if (!loginRes.ok()) {
      throw new Error(`Login failed: ${loginRes.status()}`);
    }
  } else if (!registerRes.ok()) {
    throw new Error(`Register failed: ${registerRes.status()}`);
  }

  // Save cookies (storageState) for all tests to reuse
  await context.storageState({ path: "e2e/.auth/user.json" });
});
