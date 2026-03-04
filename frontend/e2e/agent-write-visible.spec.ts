import { test, expect } from "@playwright/test";

const API_BASE = "http://localhost:8080/api";

test("agent block insert is visible in browser", async ({ page }) => {
  // Create a fresh test document via API
  const createRes = await page.request.post(`${API_BASE}/docs`, {
    data: { title: "Agent Write Test" },
  });
  const { id: docId } = await createRes.json();

  // Agent inserts a block via REST API
  await page.request.post(`${API_BASE}/docs/${docId}/blocks`, {
    data: { index: 0, type: "paragraph", content: "Nova test block" },
  });

  // Open the document in the browser
  await page.goto(`http://localhost:3000/doc/${docId}`);

  // Assert the agent-written content is visible
  await expect(page.locator("text=Nova test block")).toBeVisible({
    timeout: 5000,
  });
});
