import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import App from "./App";

afterEach(cleanup);

describe("public case study", () => {
  it("presents a real historical scan without exposing live telemetry", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={["/"]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(screen.getByRole("heading", { name: /Vulnerability data/i })).toBeInTheDocument();
    expect(screen.getByText(/Historical production evidence/i)).toBeInTheDocument();
    expect(screen.getAllByText("190")).toHaveLength(1);
    expect(screen.getByText("debian:11-slim")).toBeInTheDocument();
    expect(screen.getAllByText("d77aa2bc-e032-497f-a4ef-defd6ddbff21")).toHaveLength(2);
    expect(screen.getByRole("heading", { name: /Inspect what Lambda persisted/i })).toBeInTheDocument();
    expect(screen.getByText("CVE-2022-3715")).toBeInTheDocument();
  });

  it("replays the public fixture without submitting a scan request", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={["/"]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getAllByRole("button", { name: /Inspect public scan replay/i })[0]);
    expect(screen.getAllByText("RUNNING").length).toBeGreaterThan(0);
    expect(screen.getByText(/EVENT 51c9d394/i)).toBeInTheDocument();
    expect(screen.getAllByText("d77aa2bc-e032-497f-a4ef-defd6ddbff21").length).toBeGreaterThan(0);
  });

  it("filters the recorded finding sample without a backend request", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={["/"]}>
          <App />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.change(screen.getByLabelText("Search recorded evidence"), {
      target: { value: "bash" },
    });
    expect(screen.getByText("CVE-2022-3715")).toBeInTheDocument();
    expect(screen.queryByText("CVE-2011-3374")).not.toBeInTheDocument();
  });

  it("orders recorded findings from critical through unknown", () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter initialEntries={["/"]}><App /></MemoryRouter>
      </QueryClientProvider>,
    );
    const severities = screen.getByRole("region", { name: "Recorded findings" }).querySelectorAll(".finding-heading .badge");
    expect(severities[0]).toHaveTextContent("CRITICAL");
    expect(severities[severities.length - 1]).toHaveTextContent("UNKNOWN");
  });
});
