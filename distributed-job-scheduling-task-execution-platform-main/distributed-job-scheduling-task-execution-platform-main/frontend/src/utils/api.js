const API_BASE = '/api';

export async function apiRequest(endpoint, options = {}) {
  const token = localStorage.getItem('token');
  const headers = {
    'Content-Type': 'application/json',
    ...(token && { 'Authorization': `Bearer ${token}` }),
    ...options.headers,
  };

  const response = await fetch(`${API_BASE}${endpoint}`, {
    ...options,
    headers,
  });

  if (response.status === 429) {
    throw new Error('Rate limit exceeded. Please try again later.');
  }

  if (response.status === 401) {
    localStorage.removeItem('token');
    window.location.reload();
    throw new Error('Unauthorized');
  }

  if (response.status === 404) {
    throw new Error('Resource not found');
  }

  if (response.status === 409) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.error || 'Conflict');
  }

  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.error || `HTTP ${response.status}`);
  }

  // Handle 204 No Content
  if (response.status === 204) {
    return null;
  }

  return response.json();
}

export const jobsApi = {
  list: (page = 0, size = 20, status = null) => {
    let url = `/jobs?page=${page}&size=${size}`;
    if (status) url += `&status=${status}`;
    return apiRequest(url);
  },
  get: (id) => apiRequest(`/jobs/${id}`),
  create: (data, idempotencyKey = null) => {
    const headers = {};
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    return apiRequest('/jobs', {
      method: 'POST',
      body: JSON.stringify(data),
      headers,
    });
  },
  cancel: (id) => apiRequest(`/jobs/${id}/cancel`, { method: 'POST' }),
  retry: (id) => apiRequest(`/jobs/${id}/retry`, { method: 'POST' }),
  getAttempts: (id) => apiRequest(`/jobs/${id}/attempts`),
  getResult: (id) => apiRequest(`/jobs/${id}/result`),
  recover: (id) => apiRequest(`/jobs/${id}/recover`, { method: 'POST' }),
  adminList: (page = 0, size = 20, status = null) => {
    let url = `/jobs/admin/jobs?page=${page}&size=${size}`;
    if (status) url += `&status=${status}`;
    return apiRequest(url);
  },
};

export const authApi = {
  login: (username, password) =>
    apiRequest('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  register: (username, password, role = 'USER') =>
    apiRequest('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password, role }),
    }),
};
