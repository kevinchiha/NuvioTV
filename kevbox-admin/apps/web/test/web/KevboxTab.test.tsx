import { afterEach, expect, test, vi } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { KevboxTab } from "../../src/web/components/KevboxTab.js";

afterEach(cleanup);

test("not enrolled: shows enroll form, submit calls onSave with the key", () => {
  const onSave = vi.fn();
  render(<KevboxTab kevbox={null} busy={false} onSave={onSave} onUnenroll={vi.fn()} onRevealUrl={vi.fn()} />);
  fireEvent.change(screen.getByLabelText("kevbox-key"), { target: { value: "PMK" } });
  fireEvent.click(screen.getByText("Enroll"));
  expect(onSave).toHaveBeenCalledWith({ premiumizeKey: "PMK" });
});

test("enrolled: shows status, reveal button calls onRevealUrl", () => {
  const onRevealUrl = vi.fn();
  render(
    <KevboxTab
      kevbox={{ name: "alice", enrolled: true, hasKey: true }}
      busy={false} onSave={vi.fn()} onUnenroll={vi.fn()} onRevealUrl={onRevealUrl}
    />,
  );
  expect(screen.getByText(/Enrolled/)).toBeTruthy();
  fireEvent.click(screen.getByText("Reveal / copy URL"));
  expect(onRevealUrl).toHaveBeenCalled();
});
