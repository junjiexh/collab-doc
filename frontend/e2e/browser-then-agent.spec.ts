import { test, expect } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";

test("browser typing + agent insert coexist", async ({ page }) => {
  // 1. Create a fresh document
  const createRes = await page.request.post(`${API_BASE}/docs`, {
    data: { title: "Browser Then Agent Test" },
  });
  const { id: docId } = await createRes.json();

  // 2. Open document in browser and wait for editor
  await page.goto(`http://localhost:3000/doc/${docId}`);
  const editor = page.locator(".bn-editor");
  await expect(editor).toBeVisible({ timeout: 8000 });

  // 3. Type something in the editor
  await editor.click();
  await page.keyboard.type("Hello from browser");

  // 4. Verify browser text appeared
  await expect(page.locator("text=Hello from browser")).toBeVisible({
    timeout: 3000,
  });

  // 5. Agent inserts a block via REST API (new v2 format)
  await page.request.post(`${API_BASE}/docs/${docId}/blocks`, {
    data: { type: "paragraph", content: "Hello from agent", position: "end" },
  });

  // 6. Both texts should be visible in the editor
  await expect(page.locator("text=Hello from agent")).toBeVisible({
    timeout: 5000,
  });
  await expect(page.locator("text=Hello from browser")).toBeVisible();
});
