import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'RAOfflineProxy',
  description: 'Play RetroAchievements offline on Android — documentation',
  base: '/',

  appearance: 'dark',

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/logo.png' }],
  ],

  themeConfig: {
    logo: '/logo.png',
    siteTitle: 'RAOfflineProxy',

    nav: [
      { text: 'Guide', link: '/introduction', activeMatch: '^/(?!$)' },
      {
        text: 'GitHub',
        link: 'https://github.com/misantronic/RAOfflineProxy',
      },
    ],

    sidebar: [
      {
        text: 'Getting Started',
        items: [
          { text: 'Introduction', link: '/introduction' },
          { text: 'Installation & Setup', link: '/installation' },
        ],
      },
      {
        text: 'Features',
        items: [
          { text: 'RetroArch CFG Patching', link: '/cfg-patching' },
          { text: 'Caching Games', link: '/caching-games' },
          { text: 'Pending Awards', link: '/pending-awards' },
          { text: 'Anti-Tamper Hash Chain', link: '/hash-chain' },
          { text: 'Settings & Auto-start', link: '/settings' },
        ],
      },
      {
        text: 'Help',
        items: [{ text: 'Troubleshooting / FAQ', link: '/troubleshooting' }],
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
      message: 'Released under the MIT License.',
      copyright: 'Approved by RetroAchievements.org',
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/misantronic/RAOfflineProxy' },
    ],
  },
})
