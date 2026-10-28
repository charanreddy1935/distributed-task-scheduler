package com.distributed.jobplatform.repository;

import com.distributed.jobplatform.entity.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JobAttemptRepository extends JpaRepository<JobAttempt, UUID> {
    List<JobAttempt> findByJobIdOrderByAttemptNumberDesc(UUID jobId);
    int countByJobId(UUID jobId);
}
