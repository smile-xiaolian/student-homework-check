let accessToken: string | null = sessionStorage.getItem('edu.accessToken')

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
  if (token) sessionStorage.setItem('edu.accessToken', token)
  else sessionStorage.removeItem('edu.accessToken')
}
