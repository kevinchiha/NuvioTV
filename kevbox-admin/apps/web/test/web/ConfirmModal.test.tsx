import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ConfirmModal } from "../../src/web/components/ConfirmModal.js";

describe("ConfirmModal", () => {
  it("disables Confirm until the user types the exact word", () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmModal title="Danger" message="Do the thing?" onConfirm={onConfirm} onCancel={() => {}} />,
    );
    const confirmBtn = screen.getByRole("button", { name: "Confirm" });
    expect(confirmBtn).toBeDisabled();

    fireEvent.change(screen.getByLabelText("confirm-input"), { target: { value: "nope" } });
    expect(confirmBtn).toBeDisabled();

    fireEvent.change(screen.getByLabelText("confirm-input"), { target: { value: "CONFIRM" } });
    expect(confirmBtn).toBeEnabled();
    fireEvent.click(confirmBtn);
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it("fires onCancel from the Cancel button", () => {
    const onCancel = vi.fn();
    render(
      <ConfirmModal title="Danger" message="Do the thing?" onConfirm={() => {}} onCancel={onCancel} />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });
});
