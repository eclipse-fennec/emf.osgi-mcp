---
layout: home

hero:
  name: Fennec MCP
  text: Model Context Protocol for OSGi
  tagline: A lightweight, reactive MCP server framework for OSGi runtimes — expose live OSGi services and EMF models to AI/LLM clients over HTTP, built on Declarative Services and Project Reactor.
  image:
    src: /fennec-logo.png
    alt: Eclipse Fennec logo
  actions:
    - theme: brand
      text: Introduction
      link: /guides/00-introduction
    - theme: alt
      text: Development Guide
      link: /guides/development-guide
    - theme: alt
      text: View on GitHub
      link: https://github.com/eclipse-fennec/emf.osgi-mcp

features:
  - icon: 🧩
    title: Whiteboard MCP tools
    details: "Register a DS component implementing MCPTool and it is discovered, aggregated by an MCPToolProvider, and exposed over the MCP protocol — no central registry to edit. LDAP target filters select which tools a server publishes."
    link: /guides/01-architecture
    linkText: Architecture
  - icon: ⚡
    title: Reactive execution
    details: "Every tool call returns a Mono<CallToolResult> on Project Reactor with bounded-elastic scheduling and per-request timeouts — blocking work is isolated, interruptible and torn down when a request is cancelled."
    link: /guides/01-architecture
    linkText: Architecture
  - icon: 🐚
    title: Gogo shell over MCP
    details: "A ready-to-use Gogo Shell MCP server exposes Apache Felix Gogo commands as tools to inspect bundles, DS components, services and configuration in a live runtime. Development-time tooling — not for production."
    link: /guides/development-guide
    linkText: Development guide
  - icon: 🗂️
    title: EMF model tools
    details: "Eleven tools to build, populate, validate and serialize EMF model instances in session-scoped datasets with replayable recipes — behind a deny-all EPackage/EClass allow-list and hard per-session resource caps."
    link: /guides/01-architecture
    linkText: Architecture
  - icon: 🔒
    title: Secure by default
    details: "The shipped runtime binds to localhost, an authentication filter rejects every non-loopback request unless a bearer token is configured, and the tools enforce output, concurrency and timeout limits against resource exhaustion."
    link: /guides/02-security
    linkText: Security
  - icon: 🧱
    title: Pure OSGi, no platform
    details: "Java 21, OSGi Declarative Services, the OSGi HTTP Whiteboard and the OSGi Configurator — no Eclipse Platform, no Xtext, no Guava, no Apache Commons. Each module is a bnd bundle project."
    link: /guides/01-architecture
    linkText: Architecture
---

## About Fennec MCP

Fennec MCP (`org.eclipse.fennec.mcp`) is a **reactive MCP (Model Context Protocol)
server framework** for OSGi environments in the
[Eclipse Fennec](https://github.com/eclipse-fennec) ecosystem. It lets AI/LLM
clients interact with a live OSGi runtime over the standard MCP protocol via HTTP.

Two concrete servers ship on top of the core whiteboard API:

- a **Gogo Shell MCP server** that exposes Apache Felix Gogo commands as MCP tools
  (a development- and debugging-time tool — see the [security guide](/guides/02-security)); and
- an **EMF Model MCP server** that builds, validates and serializes EMF instances
  from allow-listed metamodels.

Key design decisions (see the [architecture guide](/guides/01-architecture)):

- **OSGi Whiteboard everywhere** — tools, tool providers and HTTP servers are DS
  services wired by LDAP target filters and the OSGi Configurator, not hard-coded.
- **Reactive, interruptible execution** — tool calls run as `Mono` on a bounded
  scheduler; a timed-out call is cancelled *and* its worker torn down.
- **Secure and bounded by default** — loopback binding, an authentication filter,
  and explicit output/concurrency/timeout caps.

Internal design notes (the EMF model-tools plan, issue reports) live in the
[`docs/` folder on GitHub](https://github.com/eclipse-fennec/emf.osgi-mcp/tree/main/docs).
