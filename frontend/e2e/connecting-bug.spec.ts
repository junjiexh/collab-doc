import { test, expect } from "@playwright/test";

/**
 * Reproduces the "Connecting..." bug:
 *   1. Open http://localhost:3000/
 *   2. Click any document (or create a new one)
 *   3. Editor should sync and show BlockNote — but instead stays stuck on "Connecting..."
 */
test("opening a document should sync and show the editor, not stay on Connecting...", async ({
  page,
}) => {
  // 1. Go to the document list page
  await page.goto("/");
  await expect(page.locator("h1")).toHaveText("Documents");

  // 2. Open a document — click an existing doc title, or create new if none exist
  const existingDoc = page.locator("strong").first();
  if (await existingDoc.isVisible().catch(() => false)) {
    await existingDoc.click();
  } else {
    await page.getByText("+ New Document").click();
  }

  // 3. We should land on the editor page
  await expect(page).toHaveURL(/\/doc\//);

  // 4. The bug: "Connecting..." never goes away.
  //    We expect it to disappear within 8 seconds and the editor to appear.
  //    If this test FAILS (times out), the bug is reproduced.
  const connectingText = page.getByText("Connecting...");
  await expect(connectingText).toBeHidden({ timeout: 8000 });

  // 5. Verify the BlockNote editor actually rendered
  const editor = page.locator(".bn-editor");
  await expect(editor).toBeVisible({ timeout: 3000 });
});
