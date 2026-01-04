package org.example.rag.repository;

import org.example.rag.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KbDocumentRepository extends JpaRepository<KbDocument,Long> {
    // 查询某组下是否存在该 Hash 的文件
    boolean existsByFileHashAndPermissionGroup(String fileHash, String permissionGroup);
    /**
     * 级联删除文档片段
     *
     * @param docId 文档ID
     * @return 删除的片段数量
     */
    @Modifying
    @Query(value = "DELETE FROM document_chunks WHERE doc_id = :docId", nativeQuery = true)
    int deleteChunksByDocId(@Param("docId") Long docId);
}
