package org.example.rag.repository;

import org.example.rag.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument,Long> {
    // 查询某组下是否存在该 Hash 的文件
    boolean existsByFileHashAndPermissionGroup(String fileHash, String permissionGroup);
}
