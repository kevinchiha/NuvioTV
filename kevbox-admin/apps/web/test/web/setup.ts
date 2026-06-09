import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

// vitest runs without `globals: true`, so @testing-library/react's automatic cleanup
// (registered via a global afterEach) never fires and rendered DOM leaks between tests.
// Register cleanup explicitly so each test starts from a clean document.
afterEach(() => {
  cleanup();
});
