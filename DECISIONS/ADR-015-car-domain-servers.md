# ADR-015 — Car functions as MCP-shaped domain servers inside the app

Status: **Accepted** (2026-09-30, product owner chose option A: "a look good")
Builds on: ADR-009 (provider-neutral contract), ADR-013 (Gemini default), ADR-012 (fuzzy requests).

## Decision

1. The realtime model (Gemini Live) stays the single agent: it interprets intent, calls tools and
   speaks. No second "brain" model behind it (option C rejected: one more model call per request).
2. Car functions are reorganised into **in-process domain servers** with one MCP-shaped interface:
   `listTools()`, `callTool(name, args) -> result`, `state()`. Initial servers: body (windows,
   seats — simulated), climate, navigation, media, phone, vision, comfort (scenarios), speech.
3. A **server registry** replaces the hand-maintained declaration list: it collects every server's
   tools for the provider setup and routes each call to its owner. `RealtimeToolCatalog` and
   `AndroidToolDispatcher` become the registry; no second list survives (delete what you replace).
4. The wire MCP protocol (option B) is not built now; the interface is shaped so it can be added
   without redesign when something outside the app needs the servers.
5. Unchanged: the claim gate (only an `ok=true` result may be claimed), `CapabilityCatalog` as
   capability truth, one voice per stage, ADR-014 guidance, the owner's test-run policy.

## Consequence for B-029

Windows, seat and scenarios are built as the first body/comfort servers, after the registry exists.
