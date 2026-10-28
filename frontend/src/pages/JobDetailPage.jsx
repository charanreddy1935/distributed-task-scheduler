import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { jobsApi } from '../utils/api'

const STATUS_COLORS = {
  CREATED: 'bg-gray-100 text-gray-800',
  SCHEDULED: 'bg-blue-100 text-blue-800',
  QUEUED: 'bg-yellow-100 text-yellow-800',
  RUNNING: 'bg-purple-100 text-purple-800',
  SUCCESS: 'bg-green-100 text-green-800',
  FAILED: 'bg-red-100 text-red-800',
  RETRYING: 'bg-orange-100 text-orange-800',
  DEAD: 'bg-red-200 text-red-900',
  CANCELLED: 'bg-gray-200 text-gray-600',
}

export default function JobDetailPage({ token, user }) {
  const { id } = useParams()
  const navigate = useNavigate()
  const [job, setJob] = useState(null)
  const [attempts, setAttempts] = useState([])
  const [result, setResult] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    loadJob()
  }, [id])

  const loadJob = async () => {
    try {
      const [jobData, attemptsData, resultData] = await Promise.all([
        jobsApi.get(id),
        jobsApi.getAttempts(id),
        jobsApi.getResult(id).catch(() => null),
      ])
      setJob(jobData)
      setAttempts(attemptsData)
      setResult(resultData)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  const handleCancel = async () => {
    try {
      await jobsApi.cancel(id)
      loadJob()
    } catch (err) {
      setError(err.message)
    }
  }

  const handleRetry = async () => {
    try {
      await jobsApi.retry(id)
      loadJob()
    } catch (err) {
      setError(err.message)
    }
  }

  const handleRecover = async () => {
    try {
      await jobsApi.recover(id)
      loadJob()
    } catch (err) {
      setError(err.message)
    }
  }

  if (loading) return <div className="text-center py-8">Loading...</div>
  if (error) return <div className="text-center py-8 text-red-600">Error: {error}</div>
  if (!job) return <div className="text-center py-8">Job not found</div>

  return (
    <div className="max-w-4xl mx-auto">
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">{job.name}</h1>
        <div className="space-x-2">
          {['CREATED', 'SCHEDULED', 'QUEUED'].includes(job.status) && (
            <button onClick={handleCancel} className="px-3 py-1 border border-red-300 text-sm rounded text-red-700 hover:bg-red-50">
              Cancel
            </button>
          )}
          {['FAILED', 'DEAD'].includes(job.status) && (
            <button onClick={handleRetry} className="px-3 py-1 border border-orange-300 text-sm rounded text-orange-700 hover:bg-orange-50">
              Retry
            </button>
          )}
          {job.status === 'DEAD' && user?.role === 'ADMIN' && (
            <button onClick={handleRecover} className="px-3 py-1 border border-green-300 text-sm rounded text-green-700 hover:bg-green-50">
              Recover (Admin)
            </button>
          )}
          <button onClick={loadJob} className="px-3 py-1 border border-gray-300 text-sm rounded text-gray-700 hover:bg-gray-50">
            Refresh
          </button>
        </div>
      </div>

      {/* Job Info */}
      <div className="bg-white shadow rounded-lg p-6 mb-6">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <div className="text-sm font-medium text-gray-500">Status</div>
            <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium mt-1 ${STATUS_COLORS[job.status]}`}>
              {job.status}
            </span>
          </div>
          <div>
            <div className="text-sm font-medium text-gray-500">Type</div>
            <div className="text-sm text-gray-900 mt-1">{job.type}</div>
          </div>
          <div>
            <div className="text-sm font-medium text-gray-500">Priority</div>
            <div className="text-sm text-gray-900 mt-1">{job.priority}</div>
          </div>
          <div>
            <div className="text-sm font-medium text-gray-500">Retries</div>
            <div className="text-sm text-gray-900 mt-1">{job.retryCount} / {job.maxRetries}</div>
          </div>
          <div>
            <div className="text-sm font-medium text-gray-500">Created</div>
            <div className="text-sm text-gray-900 mt-1">{new Date(job.createdAt).toLocaleString()}</div>
          </div>
          <div>
            <div className="text-sm font-medium text-gray-500">Started</div>
            <div className="text-sm text-gray-900 mt-1">{job.startedAt ? new Date(job.startedAt).toLocaleString() : '-'}</div>
          </div>
          <div>
            <div className="text-sm font-medium text-gray-500">Completed</div>
            <div className="text-sm text-gray-900 mt-1">{job.completedAt ? new Date(job.completedAt).toLocaleString() : '-'}</div>
          </div>
          <div>
            <div className="text-sm font-medium text-gray-500">Timeout</div>
            <div className="text-sm text-gray-900 mt-1">{job.timeoutSeconds}s</div>
          </div>
        </div>

        {job.payload && (
          <div className="mt-4">
            <div className="text-sm font-medium text-gray-500">Payload</div>
            <pre className="mt-1 text-sm text-gray-900 bg-gray-50 p-3 rounded overflow-x-auto">
              {job.payload}
            </pre>
          </div>
        )}

        {job.idempotencyKey && (
          <div className="mt-4">
            <div className="text-sm font-medium text-gray-500">Idempotency Key</div>
            <div className="text-sm text-gray-900 mt-1 font-mono">{job.idempotencyKey}</div>
          </div>
        )}
      </div>

      {/* Result */}
      {result && (
        <div className="bg-white shadow rounded-lg p-6 mb-6">
          <h2 className="text-lg font-medium text-gray-900 mb-4">Result</h2>
          <div className={`p-3 rounded ${result.success ? 'bg-green-50' : 'bg-red-50'}`}>
            <div className="text-sm font-medium text-gray-500">
              {result.success ? 'Success' : 'Failed'}
            </div>
            {result.resultPayload && (
              <pre className="mt-1 text-sm text-gray-900 overflow-x-auto">
                {result.resultPayload}
              </pre>
            )}
            {result.errorMessage && (
              <pre className="mt-1 text-sm text-red-700 overflow-x-auto">
                {result.errorMessage}
              </pre>
            )}
          </div>
        </div>
      )}

      {/* Attempts */}
      <div className="bg-white shadow rounded-lg p-6">
        <h2 className="text-lg font-medium text-gray-900 mb-4">Attempts ({attempts.length})</h2>
        {attempts.length === 0 ? (
          <p className="text-sm text-gray-500">No attempts yet.</p>
        ) : (
          <div className="space-y-3">
            {attempts.map((attempt) => (
              <div key={attempt.id} className="border border-gray-200 rounded p-3">
                <div className="flex justify-between items-start">
                  <div>
                    <div className="text-sm font-medium text-gray-900">
                      Attempt #{attempt.attemptNumber}
                    </div>
                    <div className="text-xs text-gray-500 mt-1">
                      Status: {attempt.status} | 
                      Started: {attempt.startedAt ? new Date(attempt.startedAt).toLocaleString() : '-'} |
                      Completed: {attempt.completedAt ? new Date(attempt.completedAt).toLocaleString() : '-'}
                    </div>
                  </div>
                  <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                    attempt.status === 'COMPLETED' ? 'bg-green-100 text-green-800' :
                    attempt.status === 'FAILED' ? 'bg-red-100 text-red-800' :
                    attempt.status === 'TIMED_OUT' ? 'bg-orange-100 text-orange-800' :
                    'bg-blue-100 text-blue-800'
                  }`}>
                    {attempt.status}
                  </span>
                </div>
                {attempt.errorMessage && (
                  <pre className="mt-2 text-xs text-red-700 bg-red-50 p-2 rounded overflow-x-auto">
                    {attempt.errorMessage}
                  </pre>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
