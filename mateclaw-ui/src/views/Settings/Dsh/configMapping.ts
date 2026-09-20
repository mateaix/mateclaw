export interface DshConfigForm {
  executable_path: string
  cordis_config_path: string
  working_directory: string
  base_url: string
  model_name: string
  api_key: string
}

const MANAGED_KEYS = {
  executable_path: 'dsh.executable_path',
  cordis_config_path: 'dsh.cordis_config_path',
  working_directory: 'dsh.working_directory',
  base_url: 'dsh.base_url',
  model_name: 'dsh.model_name',
  api_key: 'dsh.api_key',
} as const satisfies Record<keyof DshConfigForm, string>

const FORM_FIELDS = Object.keys(MANAGED_KEYS) as Array<keyof DshConfigForm>

export function createEmptyDshConfigForm(): DshConfigForm {
  return {
    executable_path: '',
    cordis_config_path: '',
    working_directory: '',
    base_url: '',
    model_name: '',
    api_key: '',
  }
}

export function managedConfigToForm(
  managed: Record<string, string | null | undefined>,
): DshConfigForm {
  const form = createEmptyDshConfigForm()
  for (const field of FORM_FIELDS) {
    form[field] = managed[MANAGED_KEYS[field]] || ''
  }
  if (form.api_key.startsWith('****')) form.api_key = ''
  return form
}

export function formToManagedConfig(form: DshConfigForm): Record<string, string> {
  return Object.fromEntries(
    FORM_FIELDS.map(field => [MANAGED_KEYS[field], form[field]]),
  )
}
