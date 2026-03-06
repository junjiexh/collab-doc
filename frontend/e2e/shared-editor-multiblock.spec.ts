import { test, expect, type BrowserContext, type Page } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";

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

test("shared EDITOR can edit non-first block in a document with existing content", async ({
  browser,
}) => {
  const suffix = Date.now();
  const alice = { username: `alice_mb_${suffix}`, password: "testpass123" };
  const bob = { username: `bob_mb_${suffix}`, password: "testpass123" };

  const aliceCtx: BrowserContext = await browser.newContext();
  const bobCtx: BrowserContext = await browser.newContext();
  const alicePage: Page = await aliceCtx.newPage();
  const bobPage: Page = await bobCtx.newPage();

  try {
    // Register both users
    await registerOrLogin(alicePage, alice.username, alice.password);
    await registerOrLogin(bobPage, bob.username, bob.password);

    // Alice creates a document
    const createRes = await alicePage.request.post(`${API_BASE}/docs`, {
      data: { title: "Multiblock Shared Doc" },
    });
    const { id: docId } = await createRes.json();

    // Alice opens the document and writes multiple blocks
    await alicePage.goto(`http://localhost:3000/doc/${docId}`);
    const aliceEditor = alicePage.locator(".bn-editor");
    await expect(aliceEditor).toBeVisible({ timeout: 8000 });
    await aliceEditor.click();
    await alicePage.keyboard.type("Block 1 by Alice");
    await alicePage.keyboard.press("Enter");
    await alicePage.keyboard.type("Block 2 by Alice");
    await alicePage.keyboard.press("Enter");
    await alicePage.keyboard.type("Block 3 by Alice");

    // Verify Alice's content
    await expect(alicePage.locator("text=Block 1 by Alice")).toBeVisible({ timeout: 3000 });
    await expect(alicePage.locator("text=Block 2 by Alice")).toBeVisible();
    await expect(alicePage.locator("text=Block 3 by Alice")).toBeVisible();

    // Wait for content to persist to server
    await alicePage.waitForTimeout(500);

    // Alice shares with Bob as EDITOR
    const shareRes = await alicePage.request.post(
      `${API_BASE}/docs/${docId}/permissions`,
      { data: { username: bob.username, permission: "EDITOR" } }
    );
    expect(shareRes.status()).toBe(201);

    // Bob opens the same document
    await bobPage.goto(`http://localhost:3000/doc/${docId}`);
    const bobEditor = bobPage.locator(".bn-editor");
    await expect(bobEditor).toBeVisible({ timeout: 8000 });

    // Bob should see all of Alice's content
    await expect(bobPage.locator("text=Block 1 by Alice")).toBeVisible({ timeout: 5000 });
    await expect(bobPage.locator("text=Block 2 by Alice")).toBeVisible();
    await expect(bobPage.locator("text=Block 3 by Alice")).toBeVisible();

    // --- Bob edits block 2: click the block, go to end of line, type ---
    // Use the paragraph element containing the text for reliable click targeting
    const block2Container = bobPage.locator("[data-content-type=paragraph]", {
      has: bobPage.locator("text=Block 2 by Alice"),
    });
    await block2Container.click();
    // Press End key to move cursor to end of line
    await bobPage.keyboard.press("End");
    await bobPage.keyboard.type(" + edited by Bob");

    // Wait for sync
    await bobPage.waitForTimeout(500);

    // Dump content for debugging
    const bobText = await bobEditor.innerText();
    console.log(`[DEBUG] Bob's editor after edit:\n${bobText}`);

    // Verify the edit landed in block 2 (the block should contain both original and new text)
    await expect(block2Container).toContainText("edited by Bob", { timeout: 5000 });

    // Verify Alice sees Bob's edit via real-time sync
    const aliceBlock2 = alicePage.locator("[data-content-type=paragraph]", {
      has: alicePage.locator("text=Block 2"),
    });
    await expect(aliceBlock2).toContainText("edited by Bob", { timeout: 5000 });

    // --- Bob adds a new block at the end ---
    const block3Container = bobPage.locator("[data-content-type=paragraph]", {
      has: bobPage.locator("text=Block 3 by Alice"),
    });
    await block3Container.click();
    await bobPage.keyboard.press("End");
    await bobPage.keyboard.press("Enter");
    await bobPage.keyboard.type("Block 4 by Bob");

    // Verify Bob's new block is visible on both sides
    await expect(bobPage.locator("text=Block 4 by Bob")).toBeVisible({ timeout: 3000 });
    await expect(alicePage.locator("text=Block 4 by Bob")).toBeVisible({ timeout: 5000 });
  } finally {
    await aliceCtx.close();
    await bobCtx.close();
  }
});
