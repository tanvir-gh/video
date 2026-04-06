package com.tanvir.video.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.tanvir.video.model.BenchmarkRun;

@Repository
public interface BenchmarkRunRepository extends JpaRepository<BenchmarkRun, Long> {
    List<BenchmarkRun> findAllByOrderByRunAtDesc();
    List<BenchmarkRun> findByClipNameOrderByRunAtDesc(String clipName);
}
