package com.antheagao.ecommerce_api.repository;

import com.antheagao.ecommerce_api.entity.ProcessedStripeEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedStripeEventRepository extends JpaRepository<ProcessedStripeEvent, String> {
}
