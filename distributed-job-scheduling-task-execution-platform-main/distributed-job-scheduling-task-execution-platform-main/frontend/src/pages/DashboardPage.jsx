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

export default function DashboardPage({ token }) {
  const [recentJobs, setRecentJobs] = useState([])
  const [stats, setStats] = useState({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    loadData()
  }, [])

  const loadData = async () => {
    try {
      const data = await jobsApi.list(0, 10)
      setRecentJobs(data.content || [])

      // Calculate stats from all jobs
      const allData = await jobsApi.list(0, 100)
      const jobs = allData.content || []
      const statusCounts = {}
      jobs.forEach(job => {
        statusCounts[job.status] = (statusCounts[job.status] || 0) + 1
      })
      setStats(statusCounts)
    } catch (err) {
      setError(err.message)
    } finally {
      setLoading(false)
    }
  }

  if (loading) return <div className="text-center py-8">Loading...</div>
  if (error) return <div className="text-center py-8 text-red-600">Error: {error}</div>

  return (
    <div>
      <h1 className="text-2xl font-bold text-gray-900 mb-6">Dashboard</h1>

      {/* Stats Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
        {Object.entries(stats).map(([status, count]) => (
          <div key={status} className="bg-white rounded-lg shadow p-4">
            <div className="text-sm font-medium text-gray-500">{status}</div>
            <div className="text-2xl font-bold text-gray-900">{count}</div>
          </div>
        ))}
      </div>

      {/* Quick Actions */}
      <div className="mb-8">
        <Link
          to="/jobs/create"
          className="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md shadow-sm text-white bg-indigo-600 hover:bg-indigo-700"
        >
          Create New Job
        </Link>
      </div>

      {/* Recent Jobs */}
      <div className="bg-white shadow rounded-lg">
        <div className="px-4 py-5 sm:px-6 border-b border-gray-200">
          <h3 className="text-lg leading-6 font-medium text-gray-900">Recent Jobs</h3>
        </div>
        <div className="overflow-hidden">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Name</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Type</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Priority</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Created</th>
              </tr>
            </thead>
            <tbody className="bg-white divide-y divide-gray-200">
              {recentJobs.map((job) => (
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
                    {new Date(job.createdAt).toLocaleString()}
                  </td>
                </tr>
              ))}
              {recentJobs.length === 0 && (
                <tr>
                  <td colSpan="5" className="px-6 py-4 text-center text-sm text-gray-500">
                    No jobs yet. Create your first job!
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
