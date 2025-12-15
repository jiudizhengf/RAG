package org.example.rag.repository;

import org.example.rag.entity.KbDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument,Long> {

}
