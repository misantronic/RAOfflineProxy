import DefaultTheme from 'vitepress/theme'
import { h } from 'vue'
import SupportKofiWidget from './components/SupportKofiWidget.vue'
import './style.css'

export default {
  ...DefaultTheme,
  enhanceApp({ app }) {
    app.component('SupportKofiWidget', SupportKofiWidget)
  },
  Layout() {
    return h('div', [
      h(DefaultTheme.Layout),
      h(SupportKofiWidget),
    ])
  },
}
