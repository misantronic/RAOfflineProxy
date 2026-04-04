---
layout: home

hero:
  name: RAOfflineProxy
  text: RetroAchievements — Offline
  tagline: A local Android proxy that lets you earn softcore achievements without an internet connection.
  image:
    src: /logo.png
    alt: RAOfflineProxy
  actions:
    - theme: brand
      text: Get Started
      link: /introduction
    - theme: alt
      text: Installation & Setup
      link: /installation
    - theme: alt
      text: View on GitHub
      link: https://github.com/misantronic/RAOfflineProxy

features:
  - icon: 📡
    title: Transparent Proxy
    details: Runs a local HTTP proxy on 127.0.0.1:8080 that intercepts RetroArch's RetroAchievements API calls — no app modifications required.
  - icon: 💾
    title: Offline Cache
    details: Game data, patch responses, and unlock lists are cached locally in a Room database so RetroArch works normally even with no connectivity.
  - icon: 🏆
    title: Award Queue
    details: Softcore achievement unlocks earned offline are queued and automatically flushed to RetroAchievements when you reconnect.
  - icon: 🔒
    title: Anti-Tamper Hash Chain
    details: Every queued award is cryptographically chained and signed with a non-exportable ECDSA key stored in Android Keystore — providing tamper evidence.
  - icon: ⚙️
    title: Auto-Patcher
    details: Automatically patches and reverts your retroarch.cfg to point at the local proxy — via direct file write, SAF, or a staging fallback.
  - icon: 🚀
    title: Auto-start on Boot
    details: Optionally starts the proxy service automatically at device boot so you never have to launch the app manually.
---
