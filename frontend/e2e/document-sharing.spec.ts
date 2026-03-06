import { test, expect, type BrowserContext, type Page } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";

/**
 * E2E test: Document sharing between two users.
 *
 * Flow:
 * 1. Alice creates a document
 * 2. Alice shares it with Bob as EDITOR
 * 3. Bob sees it in "Shared with me"
 * 4. Bob opens it and writes content
 * 5. Alice opens the same document and sees Bob's content
 * 6. Alice writes content and Bob sees it too
 */

// This test manages its own auth — skip the shared auth setup
test.use({ storageState: { cookies: [], origins: [] } });

async function registerOrLogin(
  page: Page,
  username: string,
  password: string
): Promise<void> {
  const registerRes = await page.request.post(`${API_BASE}/auth/register`, {
    data: { username, password },
  });
  if (registerRes.status() === 409) {
    const loginRes = await page.request.post(`${API_BASE}/auth/login`, {
      data: { username, password },
    });
    if (!loginRes.ok()) throw new Error(`Login failed: ${loginRes.status()}`);
  } else if (!registerRes.ok()) {
    throw new Error(`Register failed: ${registerRes.status()}`);
  }
}

test("two users can share and collaboratively edit a document", async ({
  browser,
}) => {
  // Unique usernames to avoid conflicts with other test runs
  const suffix = Date.now();
  const alice = { username: `alice_${suffix}`, password: "testpass123" };
  const bob = { username: `bob_${suffix}`, password: "testpass123" };

  // Create two independent browser contexts (two sessions)
  const aliceContext: BrowserContext = await browser.newContext();
  const bobContext: BrowserContext = await browser.newContext();
  const alicePage: Page = await aliceContext.newPage();
  const bobPage: Page = await bobContext.newPage();

  try {
    // --- Step 1: Register both users ---
    await registerOrLogin(alicePage, alice.username, alice.password);
    await registerOrLogin(bobPage, bob.username, bob.password);

    // --- Step 2: Alice creates a document ---
    const createRes = await alicePage.request.post(`${API_BASE}/docs`, {
      data: { title: "Shared Test Doc" },
    });
    expect(createRes.ok()).toBe(true);
    const { id: docId } = await createRes.json();

    // --- Step 3: Alice shares the document with Bob as EDITOR ---
    const shareRes = await alicePage.request.post(
      `${API_BASE}/docs/${docId}/permissions`,
      { data: { username: bob.username, permission: "EDITOR" } }
    );
    expect(shareRes.status()).toBe(201);

    // --- Step 4: Bob sees it in "Shared with me" sidebar ---
    await bobPage.goto(`http://localhost:3000`);
    await expect(bobPage.locator("text=Shared with me")).toBeVisible({
      timeout: 8000,
    });
    await expect(bobPage.locator("text=Shared Test Doc")).toBeVisible({
      timeout: 5000,
    });
    // Verify it shows "编辑权限" label
    await expect(bobPage.locator("text=编辑权限")).toBeVisible();

    // --- Step 5: Bob opens the document and writes content ---
    await bobPage.locator("text=Shared Test Doc").click();
    await bobPage.waitForURL(`**/doc/${docId}`);

    const bobEditor = bobPage.locator(".bn-editor");
    await expect(bobEditor).toBeVisible({ timeout: 8000 });
    await bobEditor.click();
    await bobPage.keyboard.type("Hello from Bob");
    await expect(bobPage.locator("text=Hello from Bob")).toBeVisible({
      timeout: 3000,
    });

    // --- Step 6: Alice opens the same document and sees Bob's content ---
    await alicePage.goto(`http://localhost:3000/doc/${docId}`);
    const aliceEditor = alicePage.locator(".bn-editor");
    await expect(aliceEditor).toBeVisible({ timeout: 8000 });
    await expect(alicePage.locator("text=Hello from Bob")).toBeVisible({
      timeout: 5000,
    });

    // --- Step 7: Alice writes content and Bob sees it ---
    await aliceEditor.click();
    await alicePage.keyboard.press("Enter");
    await alicePage.keyboard.type("Hello from Alice");
    await expect(alicePage.locator("text=Hello from Alice")).toBeVisible({
      timeout: 3000,
    });

    // Bob should see Alice's content via WebSocket sync
    await expect(bobPage.locator("text=Hello from Alice")).toBeVisible({
      timeout: 5000,
    });

    // Both texts visible on both sides
    await expect(alicePage.locator("text=Hello from Bob")).toBeVisible();
    await expect(bobPage.locator("text=Hello from Bob")).toBeVisible();
    await expect(alicePage.locator("text=Hello from Alice")).toBeVisible();
    await expect(bobPage.locator("text=Hello from Alice")).toBeVisible();
  } finally {
    await aliceContext.close();
    await bobContext.close();
  }
});
