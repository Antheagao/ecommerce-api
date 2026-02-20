package com.antheagao.ecommerce_api.repository;

import com.antheagao.ecommerce_api.entity.OrderNumberSeq;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface OrderNumberSeqRepository extends JpaRepository<OrderNumberSeq, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM OrderNumberSeq s WHERE s.id = 1")
    OrderNumberSeq getForUpdate();
}
