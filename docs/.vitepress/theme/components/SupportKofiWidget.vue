<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'

declare global {
  interface Window {
    kofiWidgetOverlay?: {
      draw: (username: string, options: Record<string, string>) => void
    }
  }
}

function drawWidget() {
  window.kofiWidgetOverlay?.draw('misantronic', {
    type: 'floating-chat',
    'floating-chat.donateButton.text': 'Tip Me',
    'floating-chat.donateButton.background-color': '#323842',
    'floating-chat.donateButton.text-color': '#fff',
  })
}

function ensureScript() {
  const script = document.createElement('script')
  script.id = 'kofi-overlay-widget-script';
  script.src = 'https://storage.ko-fi.com/cdn/scripts/overlay-widget.js'
  script.async = true
  script.addEventListener('load', drawWidget, { once: true })
  document.head.appendChild(script)
}

onMounted(() => {
  ensureScript()
})

onBeforeUnmount(() => {})
</script>

<template>
  <div class="support-kofi-widget" />
</template>
