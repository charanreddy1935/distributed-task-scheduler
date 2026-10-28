package com.distributed.jobplatform.repository;

import com.distributed.jobplatform.entity.Job;
import com.distributed.jobplatform.enums.JobPriority;
import com.distributed.jobplatform.enums.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobRepository extends JpaRepository<Job, UUID> {

    Page<Job> findByOwnerId(String ownerId, Pageable pageable);

    Page<Job> findByOwnerIdAndStatus(String ownerId, JobStatus status, Pageable pageable);

    Page<Job> findByOwnerIdAndPriority(String ownerId, JobPriority priority, Pageable pageable);

    Page<Job> findByOwnerIdAndStatusAndPriority(String ownerId, JobStatus status, JobPriority priority, Pageable pageable);

    Page<Job> findByStatus(JobStatus status, Pageable pageable);

    Page<Job> findByPriority(JobPriority priority, Pageable pageable);

    /**
     * Find jobs eligible for scheduling: CREATED jobs, SCHEDULED jobs whose scheduledAt has passed,
     * or RETRYING jobs whose scheduledAt (backoff deadline) has passed.
     * Returns limited batch ordered by priority (numeric ascending = HIGH first) then scheduledAt.
     */
    @Query("SELECT j FROM Job j WHERE " +
           "(j.status = 'CREATED') OR " +
           "(j.status = 'SCHEDULED' AND j.scheduledAt <= :now) OR " +
           "(j.status = 'RETRYING' AND j.scheduledAt <= :now) " +
           "ORDER BY j.priority ASC, j.scheduledAt ASC NULLS LAST")
    List<Job> findDueJobs(@Param("now") Instant now, Pageable pageable);

    /**
     * Atomic claim: transition a job from QUEUED to RUNNING.
     * Returns the number of affected rows. If 1, the claim succeeded.
     * @Transactional declared here (not on the caller) so the atomic update always
     * runs in its own short transaction, independent of the execution path.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Job j SET j.status = 'RUNNING', j.startedAt = :now WHERE j.id = :jobId AND j.status = 'QUEUED'")
    int claimJob(@Param("jobId") UUID jobId, @Param("now") Instant now);

    /**
     * Atomic transition from RUNNING to SUCCESS.
     * Returns the number of affected rows. If 1, this thread won the race.
     * @Transactional declared here: the caller (executeJob path) is deliberately
     * non-transactional, so each atomic update owns its own short transaction.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Job j SET j.status = 'SUCCESS', j.completedAt = :now WHERE j.id = :jobId AND j.status = 'RUNNING'")
    int markJobSuccessIfRunning(@Param("jobId") UUID jobId, @Param("now") Instant now);

    /**
     * Atomic transition from RUNNING to FAILED.
     * Returns the number of affected rows. If 1, this thread won the race.
     * Prevents double-transition when timeout races with normal completion/failure.
     * @Transactional declared here: the caller (executeJob path) is deliberately
     * non-transactional, so each atomic update owns its own short transaction.
     */
    @Transactional
    @Modifying
    @Query("UPDATE Job j SET j.status = 'FAILED', j.completedAt = :now WHERE j.id = :jobId AND j.status = 'RUNNING'")
    int markJobFailedIfRunning(@Param("jobId") UUID jobId, @Param("now") Instant now);

    /**
     * Find QUEUED jobs ordered by priority then scheduled time for worker dispatch.
     */
    @Query("SELECT j FROM Job j WHERE j.status = 'QUEUED' " +
           "ORDER BY j.priority ASC, j.scheduledAt ASC NULLS LAST")
    List<Job> findQueuedJobsForDispatch(Pageable pageable);

    Optional<Job> findByIdempotencyKey(String idempotencyKey);

    long countByStatus(JobStatus status);

    long countByPriority(JobPriority priority);
}
