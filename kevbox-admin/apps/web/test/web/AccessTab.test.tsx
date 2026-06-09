import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { AccessTab } from "../../src/web/components/AccessTab.js";
import type { AccessState } from "../../src/web/lib/api.js";

const access: AccessState = {
  userId: "u1",
  active: true,
  maxDevices: 1,
  devices: [
    { deviceId: "dev-aaaaaaaa11111111", deviceName: "Acme TV 55", firstSeen: "2026-01-01T00:00:00.000Z", lastSeen: "2026-06-09T00:00:00.000Z" },
    { deviceId: "dev-bbbbbbbb22222222", deviceName: null, firstSeen: "2026-02-01T00:00:00.000Z", lastSeen: "2026-03-01T00:00:00.000Z" },
  ],
};

const noop = () => {};

describe("AccessTab", () => {
  it("renders a muted Loading state when access is null", () => {
    render(
      <AccessTab access={null} onSetActive={noop} onSetMaxDevices={noop} onRemoveDevice={noop} onRemoveAllDevices={noop} />,
    );
    expect(screen.getByText("Loading…")).toBeInTheDocument();
  });

  it("shows Disable when enabled and fires onSetActive(false)", () => {
    const onSetActive = vi.fn();
    render(
      <AccessTab access={access} onSetActive={onSetActive} onSetMaxDevices={noop} onRemoveDevice={noop} onRemoveAllDevices={noop} />,
    );
    expect(screen.getByText("Enabled")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Disable" }));
    expect(onSetActive).toHaveBeenCalledWith(false);
  });

  it("renders devices (unknown-model fallback) and fires onRemoveDevice", () => {
    const onRemoveDevice = vi.fn();
    render(
      <AccessTab access={access} onSetActive={noop} onSetMaxDevices={noop} onRemoveDevice={onRemoveDevice} onRemoveAllDevices={noop} />,
    );
    expect(screen.getByText("Acme TV 55")).toBeInTheDocument();
    expect(screen.getByText("(unknown model)")).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText("remove-dev-aaaaaaaa11111111"));
    expect(onRemoveDevice).toHaveBeenCalledWith(access.devices[0]);
  });

  it("warns when seated devices exceed the limit", () => {
    render(
      <AccessTab access={access} onSetActive={noop} onSetMaxDevices={noop} onRemoveDevice={noop} onRemoveAllDevices={noop} />,
    );
    // 2 devices, maxDevices=1 → over limit warning.
    expect(screen.getByText(/devices still authorized/)).toBeInTheDocument();
    expect(screen.getByText("2 of 1 device(s) used")).toBeInTheDocument();
  });

  it("parses the max-devices input and fires onSetMaxDevices", () => {
    const onSetMaxDevices = vi.fn();
    render(
      <AccessTab access={access} onSetActive={noop} onSetMaxDevices={onSetMaxDevices} onRemoveDevice={noop} onRemoveAllDevices={noop} />,
    );
    fireEvent.change(screen.getByLabelText("max-devices"), { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "Set" }));
    expect(onSetMaxDevices).toHaveBeenCalledWith(2);
  });
});
