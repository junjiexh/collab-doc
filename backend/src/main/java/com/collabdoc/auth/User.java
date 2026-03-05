package com.collabdoc.auth;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(name = "created_at")
    private Instant createdAt;

    protected User() {}

    public User(String username, String password) {
        this.username = username;
        this.password = password;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public Instant getCreatedAt() { return createdAt; }
}
