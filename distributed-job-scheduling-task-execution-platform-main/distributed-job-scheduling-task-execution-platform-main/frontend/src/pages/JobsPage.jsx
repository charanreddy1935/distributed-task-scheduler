import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
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

const STATUSES = ['', 'CREATED', 'SCHEDULED', 'QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'RETRYING', 'DEAD', 'CANCELLED']

export default function JobsPage({ token }) {
  const [jobs, setJobs] = useState([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [statusFilter, setStatusFilter] = useState('')
  const [loading, setLoading] = useState(true)
  const [actionError, setActionError] = useState(null)

  useEffect(() => {
    loadJobs()
  }, [page, statusFilter])

  const loadJobs = async () => {
    setLoading(true)
    try {
      const data = await jobsApi.list(page, 20, statusFilter || null)
      setJobs(data.content || [])
      setTotalPages(data.totalPages || 0)
    } catch (err) {
      console.error(err)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-gray-900">Jobs</h1>
        <Link
          to="/jobs/create"
          className="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md shadow-sm text-white bg-indigo-600 hover:bg-indigo-700"
        >
          Create Job
        </Link>
      </div>

      {/* Filters */}
      <div className="mb-4">
        <select
          value={statusFilter}
          onChange={(e) => { setStatusFilter(e.target.value); setPage(0); }}
          className="border border-gray-300 rounded-md shadow-sm py-2 px-3 text-sm focus:outline-none focus:ring-indigo-500 focus:border-indigo-500"
        >
          <option value="">All Statuses</option>
          {STATUSES.filter(Boolean).map(s => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
      </div>

      {actionError && (
        <div className="mb-4 rounded-md bg-red-50 p-4">
          <div className="text-sm text-red-700">{actionError}</div>
        </div>
      )}

      {loading ? (
        <div className="text-center py-8">Loading...</div>
      ) : (
        <div className="bg-white shadow rounded-lg overflow-hidden">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Name</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Type</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Priority</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Retries</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Created</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Actions</th>
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-200">
              {jobs.map((job) => (
                <tr key={job.id}>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <Link to={`/jobs/${job.id}`} className="text-indigo-600 hover:text-indigo-900 font-medium">
                      {job.name}
                    </Link>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{job.type}</td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{job.priority}</td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${STATUS_COLORS[job.status] || 'bg-gray-100 text-gray-800'}`}>
                      {job.status}
                    </span>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                    {job.retryCount}/{job.maxRetries}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                    {new Date(job.createdAt).toLocaleString()}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm space-x-2">
                    <Link to={`/jobs/${job.id}`} className="text-indigo-600 hover:text-indigo-900">View</Link>
                    {['CREATED', 'SCHEDULED', 'QUEUED'].includes(job.status) && (
                      <button
                        onClick={async () => {
                          try {
                            setActionError(null)
                            await jobsApi.cancel(job.id)
                            loadJobs()
                          } catch (err) {
                            setActionError(err.message)
                          }
                        }}
                        className="text-red-600 hover:text-red-900"
                      >
                        Cancel
                      </button>
                    )}
                    {['FAILED', 'DEAD'].includes(job.status) && (
                      <button
                        onClick={async () => {
                          try {
                            setActionError(null)
                            await jobsApi.retry(job.id)
                            loadJobs()
                          } catch (err) {
                            setActionError(err.message)
                          }
                        }}
                        className="text-orange-600 hover:text-orange-900"
                      >
                        Retry
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {jobs.length === 0 && (
                <tr>
                  <td colSpan="7" className="px-6 py-4 text-center text-sm text-gray-500">
                    No jobs found.
                  </td>
                </tr>
              )}
            </tbody>
          </table>

          {/* Pagination */}
          <div className="bg-white px-4 py-3 flex items-center justify-between border-t border-gray-200 sm:px-6">
            <div className="flex-1 flex justify-between sm:hidden">
              <button
                onClick={() => setPage(Math.max(0, page - 1))}
                disabled={page === 0}
                className="relative inline-flex items-center px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-50"
              >
                Previous
              </button>
              <button
                onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
                disabled={page >= totalPages - 1}
                className="ml-3 relative inline-flex items-center px-4 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 disabled:opacity-50"
              >
                Next
              </button>
            </div>
            <div className="hidden sm:flex-1 sm:flex sm:items-center sm:justify-between">
              <div>
                <p className="text-sm text-gray-700">
                  Page <span className="font-medium">{page + 1}</span> of <span className="font-medium">{totalPages}</span>
                </p>
              </div>
              <div>
                <button
                  onClick={() => setPage(Math.max(0, page - 1))}
                  disabled={page === 0}
                  className="relative inline-flex items-center px-2 py-2 rounded-l-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50"
                >
                  Previous
                </button>
                <button
                  onClick={() => setPage(Math.min(totalPages - 1, page + 1))}
                  disabled={page >= totalPages - 1}
                  className="relative inline-flex items-center px-2 py-2 rounded-r-md border border-gray-300 bg-white text-sm font-medium text-gray-500 hover:bg-gray-50 disabled:opacity-50"
                >
                  Next
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
