package com.distributed.jobplatform.repository;

import com.distributed.jobplatform.entity.JobResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JobResultRepository extends JpaRepository<JobResult, UUID> {
    Optional<JobResult> findByJobId(UUID jobId);
    List<JobResult> findAllByJobIdOrderByCreatedAtDesc(UUID jobId);
}
