import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'RAOfflineProxy',
  description: 'Play RetroAchievements offline on Android — documentation',
  base: '/',

  appearance: 'force-dark',

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/logo.png' }],
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
          { text: 'Linux Support', link: '/linux-support' }
        ],
      },
      {
        text: 'Help',
        items: [
          { text: 'Compatibility', link: '/compatibility' },
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
      message: 'Approved by <a href="https://retroachievements.org/" target="_blank" rel="noreferrer">RetroAchievements.org</a><br>Support the project on <a href="https://ko-fi.com/misantronic" target="_blank" rel="noreferrer">Ko-fi</a>.',
      copyright: 'Released under the GNU GENERAL PUBLIC License.'
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/misantronic/RAOfflineProxy' },
    ],
  },
})
