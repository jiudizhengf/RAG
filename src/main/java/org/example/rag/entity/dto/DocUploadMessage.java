package org.example.rag.entity.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocUploadMessage implements Serializable{
    private Long docId;
    private String LocalFilePath;
    private Long userId;
    private String group;
}
