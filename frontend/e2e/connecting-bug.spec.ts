import { test, expect } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";

/**
 * Reproduces the "Connecting..." bug:
 *   1. Open a document page
 *   2. Editor should sync and show BlockNote — but instead stays stuck on "Connecting..."
 */
test("opening a document should sync and show the editor, not stay on Connecting...", async ({
  page,
}) => {
  // 1. Create a fresh document via API
  const createRes = await page.request.post(`${API_BASE}/docs`, {
    data: { title: "Connecting Bug Test" },
  });
  const { id: docId } = await createRes.json();

  // 2. Open the document
  await page.goto(`/doc/${docId}`);

  // 3. The bug: "Connecting..." never goes away.
  //    We expect it to disappear within 8 seconds and the editor to appear.
  //    If this test FAILS (times out), the bug is reproduced.
  const connectingText = page.getByText("Connecting...");
  await expect(connectingText).toBeHidden({ timeout: 8000 });

  // 4. Verify the BlockNote editor actually rendered
  const editor = page.locator(".bn-editor");
  await expect(editor).toBeVisible({ timeout: 3000 });
});
