import { defineConfig } from 'vitepress'
import { tabsMarkdownPlugin } from 'vitepress-plugin-tabs'

export default defineConfig({
  title: 'RAOfflineProxy',
  description: 'Play RetroAchievements offline with RAOfflineProxy — documentation',
  base: '/',

  markdown: {
    config(md) {
      md.use(tabsMarkdownPlugin)
    },
  },

  appearance: 'force-dark',

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/logo.png' }],
    ['script', { async: '', src: 'https://www.googletagmanager.com/gtag/js?id=G-T3ZE6DF6Q5' }],
    ['script', {}, "window.dataLayer = window.dataLayer || []; function gtag(){dataLayer.push(arguments);} gtag('js', new Date()); gtag('config', 'G-T3ZE6DF6Q5');"],
  ],

  themeConfig: {
    logo: '/logo.png',
    siteTitle: 'RAOfflineProxy',

    nav: [
      { text: 'Guide', link: '/introduction', activeMatch: '^/(?!$)' },
      {
        text: 'Contact',
        link: '/contact',
      },
      {
        text: 'GitHub',
        link: 'https://github.com/misantronic/RAOfflineProxy',
      },
    ],

    sidebar: [
      {
        text: 'Introduction',
        link: '/introduction'
      },
      {
        text: 'Platforms',
        link: '/platforms'
      },
      {
        text: 'Android',
        collapsed: false,
        items: [
          { text: 'Installation', link: '/installation' },
          { text: 'Compatibility', link: '/compatibility' },
          { text: 'Emulator Patching', link: '/cfg-patching' },
          { text: 'Caching Games', link: '/caching-games' },
          { text: 'Pending Awards', link: '/pending-awards' },
          { text: 'Anti-Tamper Hash Chain', link: '/hash-chain' },
          { text: 'Settings & Auto-start', link: '/settings' },
          { text: 'Caveats', link: '/caveats' }
        ],
      },
      {
        text: 'Linux',
        collapsed: false,
        items: [
          { text: 'Overview', link: '/linux-support/' },
          { text: 'Installation', link: '/linux-support/installation' },
          { text: 'Emulator Patching', link: '/linux-support/cfg-patching' },
          { text: 'Caching Games', link: '/linux-support/caching-games' },
          { text: 'Pending Awards', link: '/linux-support/pending-awards' },
          { text: 'Anti-Tamper Hash Chain', link: '/linux-hash-chain' },
          { text: 'Settings & Auto-start', link: '/linux-support/settings' },
        ],
      },
      {
        text: 'Help',
        items: [
          { text: 'Troubleshooting / FAQ', link: '/troubleshooting' },
          { text: 'Contact / Feedback', link: '/contact' },
          { text: 'Privacy Policy', link: '/privacy-policy' },
        ],
      },
    ],

    search: {
      provider: 'local',
    },

    editLink: {
      pattern: 'https://github.com/misantronic/RAOfflineProxy/edit/main/docs/:path',
      text: 'Edit this page on GitHub',
    },

    footer: {
      message: 'Approved by <a href="https://retroachievements.org/" target="_blank" rel="noreferrer">RetroAchievements.org</a><br>Support the project on <a href="https://www.patreon.com/misantronic" target="_blank" rel="noreferrer">Patreon</a> or <a href="https://ko-fi.com/misantronic" target="_blank" rel="noreferrer">Ko-fi</a>.',
      copyright: 'Released under the GNU GENERAL PUBLIC License.'
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/misantronic/RAOfflineProxy' },
    ],
  },
})
