import { test as setup } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";
const TEST_USER = { username: "e2e_testuser", password: "testpass123" };

setup("authenticate", async ({ page }) => {
  // Try to register; if user already exists, login instead
  // Use page.request so cookies are stored in the browser context
  const registerRes = await page.request.post(`${API_BASE}/auth/register`, {
    data: TEST_USER,
  });

  if (registerRes.status() === 409) {
    // User already exists — login
    const loginRes = await page.request.post(`${API_BASE}/auth/login`, {
      data: TEST_USER,
    });
    if (!loginRes.ok()) {
      throw new Error(`Login failed: ${loginRes.status()}`);
    }
  } else if (!registerRes.ok()) {
    throw new Error(`Register failed: ${registerRes.status()}`);
  }

  // Save cookies (storageState) for all tests to reuse
  await page.context().storageState({ path: "e2e/.auth/user.json" });
});
