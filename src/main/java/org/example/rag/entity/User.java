package org.example.rag.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@Entity
@Table(name="users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String username;
    private String password;

    private String roles;

    public List<String> getRoleList() {
        return Arrays.asList(roles.replace("[", "").replace("]", "").replace("\"", "").split(","));
    }

}
