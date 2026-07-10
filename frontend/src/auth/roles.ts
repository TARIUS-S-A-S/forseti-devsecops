// Roles canónicos — single source of truth para la UI
// El backend siempre devuelve uno de estos string literales.

export const ROLES = {
  DUENO: 'dueno',
  CONTADORA: 'contadora',
  EMPLEADO: 'empleado',
  ADMIN: 'admin',
} as const

export type Role = (typeof ROLES)[keyof typeof ROLES]

export function isValidRole(value: unknown): value is Role {
  return typeof value === 'string' && Object.values(ROLES).includes(value as Role)
}

export function roleLabel(role: Role): string {
  switch (role) {
    case ROLES.DUENO: return 'Dueño'
    case ROLES.CONTADORA: return 'Contadora'
    case ROLES.EMPLEADO: return 'Empleado'
    case ROLES.ADMIN: return 'Administrador'
  }
}
