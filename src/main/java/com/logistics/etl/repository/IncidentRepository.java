package com.logistics.etl.repository;

import com.logistics.etl.entity.IncidentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentRepository extends JpaRepository<IncidentReport, Long> {

    List<IncidentReport> findByOrderCode(String orderCode);
}
