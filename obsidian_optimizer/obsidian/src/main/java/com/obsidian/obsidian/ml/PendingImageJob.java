package com.obsidian.obsidian.ml;

import java.time.Instant;

public class PendingImageJob {
    private String id;
    private long vaultId = 1L;
    private String notePath;
    private String imagePath;
    private String status;      // PENDING | DONE | SKIPPED
    private String contentHash;
    private Instant createdAt;
    private Instant processedAt;

    public PendingImageJob() {}

    public PendingImageJob(String notePath, String imagePath) {
        this.notePath  = notePath;
        this.imagePath = imagePath;
        this.status    = "PENDING";
    }

    public String getId()           { return id; }
    public void   setId(String id)  { this.id = id; }

    public long   getVaultId()               { return vaultId; }
    public void   setVaultId(long vaultId)   { this.vaultId = vaultId; }

    public String getNotePath()                  { return notePath; }
    public void   setNotePath(String notePath)   { this.notePath = notePath; }

    public String getImagePath()                  { return imagePath; }
    public void   setImagePath(String imagePath)  { this.imagePath = imagePath; }

    public String getStatus()                { return status; }
    public void   setStatus(String status)   { this.status = status; }

    public String getContentHash()                    { return contentHash; }
    public void   setContentHash(String contentHash)  { this.contentHash = contentHash; }

    public Instant getCreatedAt()                  { return createdAt; }
    public void    setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getProcessedAt()                    { return processedAt; }
    public void    setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
