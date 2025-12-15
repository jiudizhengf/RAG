package org.example.rag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "kb_documents")
public class KbDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String filename;

    private String filepath;
    private String filetype;
    private Long fileSize;
    // 状态机：PENDING, PROCESSING, COMPLETED, FAILED
    private String status;

    private String permissionGroup;
    // 错误信息，用于排查问题
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;


    // 自动填充创建时间
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
    }
}
