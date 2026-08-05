<script setup lang="ts">
import { computed, ref } from 'vue';

const SUPPORT_API_BASE = 'https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com';
const SUPPORT_SUBMIT_ENDPOINT = `${SUPPORT_API_BASE}/support/submit`;

type Status = 'idle' | 'submitting' | 'success' | 'error';

const status = ref<Status>('idle');
const errorMessage = ref('');

const APP_VERSIONS = [
  '1.10.0-alpha1',
  '1.9.0-alpha1',
  '1.8.0-alpha1',
  '1.7.0-alpha1'
];
const OTHER_VERSION = 'Other / older';

const appVersion = ref('');
const appVersionOther = ref('');

type HasLogId = '' | 'yes' | 'no';
const hasLogId = ref<HasLogId>('');

interface LogMetadata {
  system?: string;
  os?: string;
  device?: string;
  os_version?: string;
  app_version?: string;
  emulator?: string[];
}

const logId = ref('');
type MetadataLookupStatus = 'idle' | 'loading' | 'found' | 'not_found';
const metadataLookupStatus = ref<MetadataLookupStatus>('idle');
const detectedMetadata = ref<LogMetadata | null>(null);

// The Discord-facing /support/submit payload still has a single os_version field — "os" is
// only a separate field in the stored/looked-up metadata, for a cleaner data model there.
function combinedOsVersion(metadata: LogMetadata): string {
  return [metadata.os, metadata.os_version].filter(Boolean).join(' ');
}

async function lookupLogMetadata() {
  const id = logId.value.trim();
  if (!id) {
    metadataLookupStatus.value = 'idle';
    detectedMetadata.value = null;
    return;
  }

  metadataLookupStatus.value = 'loading';
  try {
    const response = await fetch(`${SUPPORT_API_BASE}/support/logs/${encodeURIComponent(id)}/metadata`);
    if (response.ok) {
      detectedMetadata.value = await response.json();
      metadataLookupStatus.value = 'found';
    } else {
      detectedMetadata.value = null;
      metadataLookupStatus.value = 'not_found';
    }
  } catch {
    detectedMetadata.value = null;
    metadataLookupStatus.value = 'not_found';
  }
}

// In "I have a Log ID" mode the device/OS/emulator fields aren't rendered at all, so
// submission depends entirely on the lookup having actually found something to send.
const canSubmit = computed(() => hasLogId.value === 'no' || metadataLookupStatus.value === 'found');

function buildPayload(form: HTMLFormElement): Record<string, string> {
  const data = new FormData(form);
  const field = (name: string) => (data.get(name) as string)?.trim() || '';
  const appVersionValue =
    appVersion.value === OTHER_VERSION
      ? appVersionOther.value.trim()
      : appVersion.value;

  const detected = detectedMetadata.value;

  return {
    email: field('email'),
    system: detected?.system || field('system'),
    device: detected?.device || field('device'),
    os_version: detected ? combinedOsVersion(detected) : field('os_version'),
    app_version: detected?.app_version || appVersionValue,
    emulator: detected?.emulator?.length ? detected.emulator.join(', ') : field('emulator'),
    log_id: logId.value.trim(),
    message: field('message')
  };
}

async function onSubmit(event: Event) {
  const form = event.target as HTMLFormElement;
  status.value = 'submitting';
  errorMessage.value = '';

  try {
    const response = await fetch(SUPPORT_SUBMIT_ENDPOINT, {
      method: 'POST',
      body: JSON.stringify(buildPayload(form)),
      headers: { 'Content-Type': 'application/json' }
    });

    if (response.ok) {
      status.value = 'success';
      form.reset();
      appVersion.value = '';
      appVersionOther.value = '';
      hasLogId.value = '';
      logId.value = '';
      detectedMetadata.value = null;
      metadataLookupStatus.value = 'idle';
    } else {
      const data = await response.json().catch(() => null);
      errorMessage.value =
        data?.error ||
        'Something went wrong sending the form. Please try again or use one of the other contact options.';
      status.value = 'error';
    }
  } catch {
    errorMessage.value =
      'Could not reach the support service. Please try again or use one of the other contact options.';
    status.value = 'error';
  }
}
</script>

<template>
  <form
    v-if="status !== 'success'"
    class="support-form"
    @submit.prevent="onSubmit"
  >
    <label>
      <span>Do you have a Log ID? <span class="required">*</span></span>
      <select v-model="hasLogId" required>
        <option value="" disabled>Select...</option>
        <option value="yes">I have a Log ID</option>
        <option value="no">I don't have a Log ID</option>
      </select>
    </label>

    <template v-if="hasLogId === 'yes'">
      <label>
        <span>Log ID <span class="required">*</span></span>
        <input
          v-model="logId"
          type="text"
          required
          maxlength="100"
          placeholder="From Settings/Menu → Send Logs"
          @blur="lookupLogMetadata"
        />
      </label>
      <p v-if="metadataLookupStatus === 'loading'" class="support-form-hint">
        Looking up log details...
      </p>
      <p v-else-if="metadataLookupStatus === 'not_found'" class="support-form-hint">
        No details found for that Log ID — double-check it, or switch to "I don't have a Log ID" above to enter details manually.
      </p>

      <div v-if="metadataLookupStatus === 'found' && detectedMetadata" class="support-form-detected">
        <strong>Detected from your log:</strong>
        {{ detectedMetadata.system }} &middot;
        {{ detectedMetadata.device }} &middot;
        {{ combinedOsVersion(detectedMetadata) }} &middot;
        v{{ detectedMetadata.app_version }}
        <template v-if="detectedMetadata.emulator?.length"> &middot; {{ detectedMetadata.emulator.join(', ') }}</template>
      </div>

      <label>
        <span>Email <span class="required">*</span></span>
        <input
          type="email"
          name="email"
          required
          maxlength="200"
          placeholder="you@example.com"
        />
      </label>

      <label>
        <span>What happened? <span class="required">*</span></span>
        <textarea
          name="message"
          required
          rows="6"
          maxlength="1024"
          placeholder="What did you expect, what happened instead, and steps to reproduce if known."
        ></textarea>
      </label>
    </template>

    <template v-else-if="hasLogId === 'no'">
      <label>
        <span>Email <span class="required">*</span></span>
        <input
          type="email"
          name="email"
          required
          maxlength="200"
          placeholder="you@example.com"
        />
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
          <input
            type="text"
            name="os_version"
            required
            maxlength="200"
            placeholder="e.g. Android 14, or Onion v4.3"
          />
        </label>
      </div>

      <div class="support-form-row">
        <label>
          <span>Device / model <span class="required">*</span></span>
          <input
            type="text"
            name="device"
            required
            maxlength="200"
            placeholder="e.g. Retroid Pocket Nova, Miyoo Mini Flip, Anbernic RG35XX"
          />
        </label>

        <label>
          Emulator / core
          <input
            type="text"
            name="emulator"
            maxlength="200"
            placeholder="e.g. RetroArch (Beetle PSX HW), Dolphin"
          />
        </label>
      </div>

      <label>
        <span>RAOfflineProxy version <span class="required">*</span></span>
        <select v-model="appVersion" required>
          <option value="" disabled>Select...</option>
          <option v-for="version in APP_VERSIONS" :key="version" :value="version">
            {{ version }}
          </option>
          <option :value="OTHER_VERSION">{{ OTHER_VERSION }}</option>
        </select>
      </label>

      <label v-if="appVersion === OTHER_VERSION">
        <span>Which version? <span class="required">*</span></span>
        <input
          v-model="appVersionOther"
          type="text"
          required
          maxlength="100"
          placeholder="e.g. 1.4.0-alpha1"
        />
      </label>

      <label>
        <span>What happened? <span class="required">*</span></span>
        <textarea
          name="message"
          required
          rows="6"
          maxlength="1024"
          placeholder="What did you expect, what happened instead, and steps to reproduce if known."
        ></textarea>
      </label>
    </template>

    <button v-if="hasLogId" type="submit" :disabled="status === 'submitting' || !canSubmit">
      {{ status === 'submitting' ? 'Sending...' : 'Send' }}
    </button>

    <p v-if="status === 'error'" class="support-form-error">
      {{ errorMessage }}
    </p>
  </form>

  <p v-else class="support-form-success">Thanks! Your request has been sent.</p>
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

.support-form-hint {
  margin: -8px 0 0;
  font-size: 13px;
  color: var(--vp-c-text-2);
}

.support-form-detected {
  padding: 10px 12px;
  border-radius: 6px;
  background-color: var(--vp-c-bg-soft);
  border: 1px solid var(--vp-c-divider);
  font-size: 14px;
  color: var(--vp-c-text-1);
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
