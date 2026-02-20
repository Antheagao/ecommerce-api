package com.antheagao.ecommerce_api.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "order_number_seq")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderNumberSeq {

    @Id
    @Column(name = "id")
    private Long id = 1L;

    @Column(name = "next_val", nullable = false)
    private Long nextVal = 1L;
}
