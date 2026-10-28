import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { jobsApi } from '../utils/api'

const JOB_TYPES = ['DEMO_TASK', 'REPORT', 'DATA_CLEANUP', 'EMAIL']
const PRIORITIES = ['HIGH', 'MEDIUM', 'LOW']

export default function CreateJobPage({ token }) {
  const navigate = useNavigate()
  const [form, setForm] = useState({
    name: '',
    type: 'DEMO_TASK',
    priority: 'MEDIUM',
    payload: '',
    scheduledAt: '',
    maxRetries: 3,
    timeoutSeconds: 300,
    idempotencyKey: '',
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  const handleSubmit = async (e) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const payload = {
        ...form,
        scheduledAt: form.scheduledAt ? new Date(form.scheduledAt).toISOString() : null,
        maxRetries: parseInt(form.maxRetries),
        timeoutSeconds: parseInt(form.timeoutSeconds),
      }
      // Remove empty idempotencyKey
      if (!payload.idempotencyKey) delete payload.idempotencyKey

      const result = await jobsApi.create(payload, form.idempotencyKey || null)
      navigate(`/jobs/${result.id}`)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleChange = (field) => (e) => {
    setForm(prev => ({ ...prev, [field]: e.target.value }))
  }

  return (
    <div className="max-w-2xl mx-auto">
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Create New Job</h1>

      <form onSubmit={handleSubmit} className="bg-white shadow rounded-lg p-6 space-y-6">
        {error && (
          <div className="rounded-md bg-red-50 p-4">
            <div className="text-sm text-red-700">{error}</div>
          </div>
        )}

        <div>
          <label className="block text-sm font-medium text-gray-700">Job Name *</label>
          <input
            type="text"
            required
            value={form.name}
            onChange={handleChange('name')}
            className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm py-2 px-3 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm"
            placeholder="e.g., Generate Monthly Report"
          />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700">Job Type *</label>
            <select
              value={form.type}
              onChange={handleChange('type')}
              className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm py-2 px-3 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm"
            >
              {JOB_TYPES.map(t => (
                <option key={t} value={t}>{t}</option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700">Priority</label>
            <select
              value={form.priority}
              onChange={handleChange('priority')}
              className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm py-2 px-3 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm"
            >
              {PRIORITIES.map(p => (
                <option key={p} value={p}>{p}</option>
              ))}
            </select>
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700">Payload (JSON)</label>
          <textarea
            value={form.payload}
            onChange={handleChange('payload')}
            rows={4}
            className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm py-2 px-3 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm font-mono"
            placeholder='{"key": "value"}'
          />
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700">Schedule At (optional)</label>
          <input
            type="datetime-local"
            value={form.scheduledAt}
            onChange={handleChange('scheduledAt')}
            className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm py-2 px-3 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm"
          />
          <p className="mt-1 text-xs text-gray-500">Leave empty for immediate execution</p>
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="block text-sm font-medium text-gray-700">Max Retries</label>
            <input
              type="number"
              min="1"
              max="10"
              value={form.maxRetries}
              onChange={handleChange('maxRetries')}
              className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm py-2 px-3 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm"
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700">Timeout (seconds)</label>
            <input
              type="number"
              min="1"
              max="3600"
              value={form.timeoutSeconds}
              onChange={handleChange('timeoutSeconds')}
              className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm py-2 px-3 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm"
            />
          </div>
        </div>

        <div>
          <label className="block text-sm font-medium text-gray-700">Idempotency Key (optional)</label>
          <input
            type="text"
            value={form.idempotencyKey}
            onChange={handleChange('idempotencyKey')}
            className="mt-1 block w-full border border-gray-300 rounded-md shadow-sm py-2 px-3 focus:outline-none focus:ring-indigo-500 focus:border-indigo-500 sm:text-sm"
            placeholder="Unique key to prevent duplicate jobs"
          />
        </div>

        <div className="flex justify-end space-x-3">
          <button
            type="button"
            onClick={() => navigate('/jobs')}
            className="px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={loading}
            className="px-4 py-2 border border-transparent text-sm font-medium rounded-md shadow-sm text-white bg-indigo-600 hover:bg-indigo-700 disabled:opacity-50"
          >
            {loading ? 'Creating...' : 'Create Job'}
          </button>
        </div>
      </form>
    </div>
  )
}
