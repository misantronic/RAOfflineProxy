<script setup lang="ts">
import { ref } from 'vue'

const SUPPORT_SUBMIT_ENDPOINT = 'https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com/support/submit'

type Status = 'idle' | 'submitting' | 'success' | 'error'

const status = ref<Status>('idle')
const errorMessage = ref('')

const APP_VERSIONS = ['1.8.0-alpha1', '1.7.0-alpha1', '1.6.0-alpha1', '1.5.5-alpha1']
const OTHER_VERSION = 'Other / older'

const appVersion = ref('')
const appVersionOther = ref('')

function buildPayload(form: HTMLFormElement): Record<string, string> {
  const data = new FormData(form)
  const field = (name: string) => (data.get(name) as string)?.trim() || ''
  const appVersionValue = appVersion.value === OTHER_VERSION
    ? appVersionOther.value.trim()
    : appVersion.value

  return {
    email: field('email'),
    system: field('system'),
    device: field('device'),
    os_version: field('os_version'),
    app_version: appVersionValue,
    emulator: field('emulator'),
    log_id: field('log_id'),
    message: field('message'),
  }
}

async function onSubmit(event: Event) {
  const form = event.target as HTMLFormElement
  status.value = 'submitting'
  errorMessage.value = ''

  try {
    const response = await fetch(SUPPORT_SUBMIT_ENDPOINT, {
      method: 'POST',
      body: JSON.stringify(buildPayload(form)),
      headers: { 'Content-Type': 'application/json' },
    })

    if (response.ok) {
      status.value = 'success'
      form.reset()
      appVersion.value = ''
      appVersionOther.value = ''
    } else {
      const data = await response.json().catch(() => null)
      errorMessage.value = data?.error
        || 'Something went wrong sending the form. Please try again or use one of the other contact options.'
      status.value = 'error'
    }
  } catch {
    errorMessage.value = 'Could not reach the support service. Please try again or use one of the other contact options.'
    status.value = 'error'
  }
}
</script>

<template>
  <form v-if="status !== 'success'" class="support-form" @submit.prevent="onSubmit">
    <label>
      <span>Email <span class="required">*</span></span>
      <input type="email" name="email" required placeholder="you@example.com" />
    </label>

    <div class="support-form-row">
      <label>
        <span>System <span class="required">*</span></span>
        <select name="system" required>
          <option value="" disabled selected>Select...</option>
          <option>Android</option>
          <option>Linux</option>
        </select>
      </label>

      <label>
        <span>OS / firmware version <span class="required">*</span></span>
        <input type="text" name="os_version" required placeholder="e.g. Android 14, or Onion v4.3" />
      </label>
    </div>

    <div class="support-form-row">
      <label>
        <span>Device / model <span class="required">*</span></span>
        <input type="text" name="device" required placeholder="e.g. Pixel 8, Miyoo Mini Flip, Anbernic RG35XX" />
      </label>

      <label>
        Emulator / core
        <input type="text" name="emulator" placeholder="e.g. RetroArch (Beetle PSX HW), Dolphin" />
      </label>
    </div>

    <label>
      <span>RAOfflineProxy version <span class="required">*</span></span>
      <select v-model="appVersion" required>
        <option value="" disabled>Select...</option>
        <option v-for="version in APP_VERSIONS" :key="version" :value="version">{{ version }}</option>
        <option :value="OTHER_VERSION">{{ OTHER_VERSION }}</option>
      </select>
    </label>

    <label v-if="appVersion === OTHER_VERSION">
      <span>Which version? <span class="required">*</span></span>
      <input
        v-model="appVersionOther"
        type="text"
        required
        placeholder="e.g. 1.4.0-alpha1"
      />
    </label>

    <label>
      Log ID
      <input type="text" name="log_id" placeholder="From Settings/Menu → Send Logs" />
    </label>

    <label>
      <span>What happened? <span class="required">*</span></span>
      <textarea
        name="message"
        required
        rows="6"
        placeholder="What did you expect, what happened instead, and steps to reproduce if known."
      ></textarea>
    </label>

    <button type="submit" :disabled="status === 'submitting'">
      {{ status === 'submitting' ? 'Sending...' : 'Send' }}
    </button>

    <p v-if="status === 'error'" class="support-form-error">{{ errorMessage }}</p>
  </form>

  <p v-else class="support-form-success">
    Thanks! Your request has been sent.
  </p>
</template>

<style scoped>
.support-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 24px 0;
}

.support-form label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--vp-c-text-1);
}

.support-form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 600px) {
  .support-form-row {
    grid-template-columns: 1fr;
  }
}

.required {
  color: var(--vp-c-danger-1);
}

.support-form input,
.support-form select,
.support-form textarea {
  font: inherit;
  font-weight: 400;
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid var(--vp-c-divider);
  background-color: var(--vp-c-bg-soft);
  color: var(--vp-c-text-1);
}

.support-form input:focus,
.support-form select:focus,
.support-form textarea:focus {
  outline: none;
  border-color: var(--vp-c-brand-1);
}

.support-form button {
  align-self: flex-start;
  padding: 8px 20px;
  border-radius: 6px;
  border: none;
  background-color: var(--vp-c-brand-1);
  color: var(--vp-c-white);
  font-weight: 600;
  cursor: pointer;
}

.support-form button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.support-form-error {
  color: var(--vp-c-danger-1);
  font-size: 14px;
}

.support-form-success {
  padding: 16px;
  border-radius: 6px;
  background-color: var(--vp-c-bg-soft);
  border: 1px solid var(--vp-c-divider);
}
</style>
