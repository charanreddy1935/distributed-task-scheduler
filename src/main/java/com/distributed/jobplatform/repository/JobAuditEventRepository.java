package com.distributed.jobplatform.repository;

import com.distributed.jobplatform.entity.JobAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface JobAuditEventRepository extends JpaRepository<JobAuditEvent, UUID> {

    boolean existsByEventId(String eventId);
}
